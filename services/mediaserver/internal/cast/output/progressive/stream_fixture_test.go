package progressive

import (
	"context"
	"errors"
	"io"
	"net/url"
	"sync"

	"github.com/stupside/castor/services/mediaserver/internal/cast/output"
	"github.com/stupside/castor/services/mediaserver/internal/cast/output/storage"
)

type testOpening struct {
	output.Opening
	Publisher output.Publisher
}
type testStream struct {
	*Stream
	o     testOpening
	pub   output.Publication
	done  <-chan struct{}
	close func() error
}

func (s *testStream) URL() *url.URL                  { return s.pub.URL(s.Path()) }
func (s *testStream) Produced() <-chan struct{}      { return s.done }
func (s *testStream) Wait(ctx context.Context) error { return s.Stream.Wait(ctx, s.done) }
func (s *testStream) Close() error                   { return s.close() }

func serveStreamFixture(ctx context.Context, o testOpening, dir string, reader io.Reader) (*testStream, error) {
	stream, err := NewStream(o.Opening, dir)
	if err != nil {
		return nil, err
	}
	pub, err := o.Publisher.Publish(ctx, stream.Handler())
	if err != nil {
		_ = stream.Close()
		return nil, err
	}
	life, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	var closeReader func()
	if c, ok := reader.(io.Closer); ok {
		closeReader = sync.OnceFunc(func() { _ = c.Close() })
	} else {
		closeReader = func() {}
	}
	stop := context.AfterFunc(life, closeReader)
	var result error
	go func() { defer close(done); defer closeReader(); result = stream.Write(life, reader) }()
	s := &testStream{Stream: stream, o: o, pub: pub, done: done}
	s.close = sync.OnceValue(func() error {
		cancel()
		closeReader()
		<-done
		stop()
		err := result
		if err == io.ErrClosedPipe || err == context.Canceled {
			err = nil
		}
		return errors.Join(err, pub.Close(), stream.Close())
	})
	return s, nil
}
func serveSpoolFixture(ctx context.Context, o testOpening, sp *storage.Spool, done <-chan struct{}) (*testStream, error) {
	stream := ServeSpool(o.Opening, sp)
	pub, err := o.Publisher.Publish(ctx, stream.Handler())
	if err != nil {
		return nil, err
	}
	return &testStream{Stream: stream, o: o, pub: pub, done: done, close: pub.Close}, nil
}
