package storage

import (
	"errors"
	"io"
	"io/fs"
	"path/filepath"
	"testing"
	"testing/synctest"
)

func newTestSpool(t *testing.T) *Spool {
	t.Helper()
	s, err := NewSpool(filepath.Join(t.TempDir(), "spool.ts"))
	if err != nil {
		t.Fatal(err)
	}
	return s
}

// A tail waits for growth and reads EOF only once the producer closes cleanly.
func TestATailWaitsForGrowthAndEndsOnlyAtClose(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s := newTestSpool(t)
		if _, err := s.Write([]byte("first")); err != nil {
			t.Fatal(err)
		}
		tail, err := s.TailAt(t.Context(), 5)
		if err != nil {
			t.Fatal(err)
		}
		defer tail.Close()

		read := make(chan string, 1)
		go func() {
			got, _ := io.ReadAll(tail)
			read <- string(got)
		}()
		synctest.Wait()
		select {
		case got := <-read:
			t.Fatalf("the tail returned %q before the producer ended", got)
		default:
		}

		if _, err := s.Write([]byte("second")); err != nil {
			t.Fatal(err)
		}
		synctest.Wait()
		select {
		case got := <-read:
			t.Fatalf("the tail returned %q before the producer closed", got)
		default:
		}

		s.CloseWrite(nil)
		if got := <-read; got != "second" {
			t.Errorf("a tail from byte 5 read %q, want %q", got, "second")
		}
	})
}

func TestTailReportsTheProducerError(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s := newTestSpool(t)
		tail, err := s.TailAt(t.Context(), 0)
		if err != nil {
			t.Fatal(err)
		}
		defer tail.Close()

		want := errors.New("upstream died")
		s.CloseWrite(want)
		if _, err := io.ReadAll(tail); !errors.Is(err, want) {
			t.Errorf("tail error = %v, want %v", err, want)
		}
	})
}

// Closing a tail wakes a parked read with the closure, not an EOF that would claim the producer finished.
func TestClosingATailTerminatesAReadParkedInIt(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s := newTestSpool(t)
		t.Cleanup(func() { s.CloseWrite(nil) })
		tail, err := s.TailAt(t.Context(), 0)
		if err != nil {
			t.Fatal(err)
		}
		parked := make(chan error, 1)
		go func() {
			_, err := tail.Read(make([]byte, 4))
			parked <- err
		}()
		synctest.Wait()
		if err := tail.Close(); err != nil {
			t.Fatal(err)
		}
		if err := <-parked; !errors.Is(err, fs.ErrClosed) {
			t.Errorf("a read parked in a closed tail returned %v, want fs.ErrClosed", err)
		}
	})
}

func TestCloseWritePreservesFileCloseFailure(t *testing.T) {
	s, err := NewSpool(filepath.Join(t.TempDir(), "spool"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.w.Close(); err != nil {
		t.Fatal(err)
	}
	s.CloseWrite(nil)
	if !errors.Is(s.Err(), fs.ErrClosed) {
		t.Fatalf("terminal error = %v, want file close failure", s.Err())
	}
}

// A later cleanup cannot rewrite the result readers were already promised.
func TestSpoolCompletionIsFinal(t *testing.T) {
	for _, first := range []error{nil, errors.New("producer failed")} {
		s := newTestSpool(t)
		t.Cleanup(func() { _ = s.Close() })
		s.CloseWrite(first)
		s.CloseWrite(errors.New("later teardown failed"))
		if !errors.Is(s.Err(), first) {
			t.Fatalf("completion changed: %v, want %v", s.Err(), first)
		}
		tail, err := s.TailAt(t.Context(), 0)
		if err != nil {
			t.Fatal(err)
		}
		_, err = tail.Read(make([]byte, 1))
		_ = tail.Close()
		want := first
		if want == nil {
			want = io.EOF
		}
		if !errors.Is(err, want) {
			t.Fatalf("tail = %v, want original completion %v", err, want)
		}
	}
}
