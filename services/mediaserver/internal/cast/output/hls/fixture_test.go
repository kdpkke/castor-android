package hls

import (
	"context"
	"errors"
	"io"
	"net/url"
	"os"
	"sync"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/cast/output"
)

type testOpening struct {
	output.Opening
	Publisher output.Publisher
}
type testSegments struct {
	*Segments
	o     testOpening
	pub   output.Publication
	done  <-chan struct{}
	close func() error
}

func (s *testSegments) URL() *url.URL                  { return s.pub.URL(s.Path()) }
func (s *testSegments) Produced() <-chan struct{}      { return s.done }
func (s *testSegments) Wait(ctx context.Context) error { return s.Segments.Wait(ctx, s.done) }
func (s *testSegments) Close() error                   { return s.close() }
func serveSegmentsFixture(ctx context.Context, o testOpening, dir string, reader io.ReadCloser) (*testSegments, error) {
	store, err := openSegmentStore(dir, o.Format.Tuning.Output)
	if err != nil {
		return nil, err
	}
	return publishSegmentsFixture(ctx, o, store, func(ctx context.Context) error {
		closeReader := sync.OnceFunc(func() { _ = reader.Close() })
		stop := context.AfterFunc(ctx, closeReader)
		defer stop()
		defer closeReader()
		_, err := io.Copy(io.Discard, reader)
		if ctx.Err() != nil && (errors.Is(err, os.ErrClosed) || errors.Is(err, io.ErrClosedPipe)) {
			return nil
		}
		return err
	})
}
func publishSegmentsFixture(ctx context.Context, o testOpening, store *segmentStore, write func(context.Context) error) (*testSegments, error) {
	view := &Segments{o: o.Opening, store: store, lastRequest: time.Now()}
	pub, err := o.Publisher.Publish(ctx, view.Handler())
	if err != nil {
		_ = store.Close()
		return nil, err
	}
	life, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	var result error
	go func() { defer close(done); result = write(life) }()
	s := &testSegments{Segments: view, o: o, pub: pub, done: done}
	s.close = sync.OnceValue(func() error { cancel(); <-done; return errors.Join(result, pub.Close(), view.Close()) })
	return s, nil
}
