package progressive

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"sync"
	"testing"

	"github.com/stupside/castor/services/mediaserver/internal/cast/output"
	"github.com/stupside/castor/services/mediaserver/internal/testhttp"
)

// lan is an in-memory network where these tests' devices reach a delivery, so a bubble's clock rules it.
type lan struct {
	mu     sync.Mutex
	open   map[string]*lanListener
	client *http.Client
}

func newLAN(t *testing.T) *lan {
	l := &lan{open: map[string]*lanListener{}}
	transport := &http.Transport{DialContext: l.dial}
	t.Cleanup(transport.CloseIdleConnections)
	l.client = &http.Client{Transport: transport}
	return l
}

// device is the client of the lan a delivery was opened on.
func device(o testOpening) *http.Client { return o.Publisher.(*lan).client }

func (l *lan) Publish(ctx context.Context, h http.Handler) (output.Publication, error) {
	l.mu.Lock()
	ln := &lanListener{addr: lanAddr(fmt.Sprintf("10.0.0.%d:80", len(l.open)+1)), conns: make(chan net.Conn), closed: make(chan struct{})}
	l.open[ln.addr.String()] = ln
	l.mu.Unlock()
	return testhttp.Publish(ctx, ln, h), nil
}

func (l *lan) dial(ctx context.Context, _, addr string) (net.Conn, error) {
	l.mu.Lock()
	ln := l.open[addr]
	l.mu.Unlock()
	if ln == nil {
		return nil, fmt.Errorf("no delivery at %s", addr)
	}
	near, far := net.Pipe()
	select {
	case ln.conns <- far:
		return near, nil
	case <-ln.closed:
		near.Close()
		far.Close()
		return nil, net.ErrClosed
	case <-ctx.Done():
		near.Close()
		far.Close()
		return nil, ctx.Err()
	}
}

type lanListener struct {
	addr   lanAddr
	conns  chan net.Conn
	closed chan struct{}
	once   sync.Once
}

func (l *lanListener) Accept() (net.Conn, error) {
	select {
	case c := <-l.conns:
		return c, nil
	case <-l.closed:
		return nil, net.ErrClosed
	}
}

func (l *lanListener) Close() error {
	l.once.Do(func() { close(l.closed) })
	return nil
}

func (l *lanListener) Addr() net.Addr { return l.addr }

type lanAddr string

func (lanAddr) Network() string  { return "lan" }
func (a lanAddr) String() string { return string(a) }
