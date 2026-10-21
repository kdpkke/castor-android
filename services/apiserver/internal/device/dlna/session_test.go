package dlna

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	mediav1 "github.com/stupside/castor/gen/castor/media/v1"

	"github.com/huin/goupnp"
	"github.com/huin/goupnp/soap"

	"github.com/stupside/castor/services/apiserver/internal/device"
	"github.com/stupside/castor/services/apiserver/internal/device/devicetest"
)

type actionTransport func(*http.Request) (*http.Response, error)

func (f actionTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestAPlayTimeoutNeedsConfirmationOfTheRequestedStream(t *testing.T) {
	const target = "http://192.0.2.1:8080/media/stream"
	for _, tt := range []struct {
		name, state, uri string
		play, query      string
		cancelAt         string
		want             bool
	}{
		{name: "acknowledged", want: true},
		{name: "playing despite timeout", play: "timeout", state: "PLAYING", uri: target, want: true},
		{name: "still stopped", play: "timeout", state: "STOPPED", uri: target},
		{name: "still buffering", play: "timeout", state: "TRANSITIONING", uri: target},
		{name: "playing another stream", play: "timeout", state: "PLAYING", uri: target + "-old"},
		{name: "state unanswered", play: "timeout", query: "GetTransportInfo"},
		{name: "URI unanswered", play: "timeout", state: "PLAYING", query: "GetMediaInfo"},
		{name: "explicit refusal", play: "refused", state: "PLAYING", uri: target},
		{name: "SOAP refusal", play: "fault", state: "PLAYING", uri: target},
		{name: "cancelled", play: "timeout", state: "PLAYING", uri: target, cancelAt: "Play"},
		{name: "cancelled during confirmation", play: "timeout", state: "PLAYING", query: "GetMediaInfo", cancelAt: "GetMediaInfo"},
	} {
		t.Run(tt.name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				ctx, cancel := context.WithCancel(t.Context())
				defer cancel()
				var actions []string
				client := soap.NewSOAPClient(url.URL{Scheme: "http", Host: "192.0.2.10", Path: "/control"})
				client.HTTPClient.Transport = actionTransport(func(r *http.Request) (*http.Response, error) {
					// A fake transport sees goupnp's headers before net/http canonicalizes them.
					var soapAction string
					for name, values := range r.Header {
						if strings.EqualFold(name, "SOAPAction") && len(values) > 0 {
							soapAction = values[0]
						}
					}
					action := strings.Split(strings.Trim(soapAction, `"`), "#")[1]
					actions = append(actions, action)
					if action == "Play" && tt.play == "timeout" || action == tt.query {
						if tt.cancelAt == action {
							cancel()
						}
						<-r.Context().Done()
						return nil, r.Context().Err()
					}
					if action == "Play" && tt.play == "refused" {
						return nil, errors.New("device refused Play")
					}
					if action == "Play" && tt.play == "fault" {
						body := `<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode><errorDescription>Transition not available</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>`
						return &http.Response{StatusCode: http.StatusInternalServerError, Body: io.NopCloser(strings.NewReader(body)), ContentLength: int64(len(body))}, nil
					}
					var body string
					switch action {
					case "GetTransportInfo":
						body = "<CurrentTransportState>" + tt.state + "</CurrentTransportState>"
					case "GetMediaInfo":
						body = "<CurrentURI>" + tt.uri + "</CurrentURI>"
					}
					body = `<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:` + action +
						`Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">` + body + `</u:` + action + `Response></s:Body></s:Envelope>`
					return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(body))}, nil
				})
				_, sinks := parseSinkProtocolInfo("http-get:*:video/mp2t:*")
				s := &session{transport: goupnp.ServiceClient{
					SOAPClient: client,
					Service:    &goupnp.Service{ServiceType: "urn:schemas-upnp-org:service:AVTransport:1"},
				}, sinks: sinks}
				u, _ := url.Parse(target)
				started := time.Now()
				err := s.Play(ctx, device.Playback{URL: u, Container: mediav1.Container_CONTAINER_MPEGTS})
				if (err == nil) != tt.want {
					t.Fatalf("Play() = %v, want success %v; actions %v", err, tt.want, actions)
				}
				if tt.cancelAt != "" && !errors.Is(err, context.Canceled) {
					t.Fatalf("Play() = %v, want cancellation", err)
				}
				if tt.play == "fault" {
					if _, ok := errors.AsType[*soap.SOAPFaultError](err); !ok {
						t.Fatalf("Play() = %v, want preserved SOAP fault", err)
					}
				}
				if got := time.Since(started); got > 2*actionTimeout {
					t.Errorf("Play took %v, want at most two bounded actions", got)
				}
				plays, queries := 0, 0
				for _, action := range actions {
					if action == "Play" {
						plays++
					}
					if strings.HasPrefix(action, "Get") {
						queries++
					}
				}
				if plays != 1 {
					t.Errorf("sent Play %d times, want once", plays)
				}
				if (tt.play == "" || tt.play == "refused" || tt.play == "fault" || tt.cancelAt == "Play") && queries != 0 {
					t.Errorf("queried %d times without an ambiguous timeout", queries)
				}
			})
		})
	}
}

func TestTheTransportStateEndsTheCastOnlyOncePlaybackBegan(t *testing.T) {
	for _, tt := range []struct {
		name   string
		states []string
		want   bool
	}{
		{"stop before playback is ignored", []string{"STOPPED", "TRANSITIONING", "STOPPED"}, false},
		{"stop after playing ends", []string{"PLAYING", "STOPPED"}, true},
		{"pause remains active", []string{"PLAYING", "PAUSED_PLAYBACK"}, false},
	} {
		t.Run(tt.name, func(t *testing.T) {
			var watch transportWatch
			got := false
			for _, state := range tt.states {
				got = watch.observe(state)
			}
			if got != tt.want {
				t.Errorf("observe(%v) = %v, want %v", tt.states, got, tt.want)
			}
		})
	}
}

func TestAnUnbrokenRunOfFailedPollsIsNeededToEndTheCast(t *testing.T) {
	type answer = struct {
		state string
		err   error
	}
	gone := errors.New("connect: connection refused")
	var script []answer
	for range 2 {
		for range int(device.UnreachableWindow/device.PollInterval) - 1 {
			script = append(script, answer{err: gone})
		}
		script = append(script, answer{state: "PLAYING"})
	}
	script[len(script)-1].state = "STOPPED"

	synctest.Test(t, func(t *testing.T) {
		calls := 0
		poll := func(context.Context) (string, error) {
			a := script[min(calls, len(script)-1)]
			calls++
			return a.state, a.err
		}
		if err := awaitTransportEnd(t.Context(), "Living Room", poll); err != nil {
			t.Fatalf("awaitTransportEnd() = %v, want nil: the device answered between the failures", err)
		}
		if calls != len(script) {
			t.Errorf("polled %d times, want %d", calls, len(script))
		}
	})
}

// playingDevice answers every GetTransportInfo with PLAYING, counting the polls.
type playingDevice struct{ polls atomic.Int32 }

func (r *playingDevice) RoundTrip(*http.Request) (*http.Response, error) {
	r.polls.Add(1)
	body := `<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>` +
		`<u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">` +
		`<CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>` +
		`</u:GetTransportInfoResponse></s:Body></s:Envelope>`
	return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(body)), ContentLength: int64(len(body))}, nil
}

func TestDLNAAnswersWhenTheCastEnds(t *testing.T) {
	dev := &playingDevice{}
	devicetest.AwaitsTheCastsEnd(t, func() device.Device {
		client := soap.NewSOAPClient(url.URL{Scheme: "http", Host: "192.0.2.10:2870", Path: "/control"})
		client.HTTPClient.Transport = dev
		return &session{transport: goupnp.ServiceClient{
			SOAPClient: client,
			Service:    &goupnp.Service{ServiceType: "urn:schemas-upnp-org:service:AVTransport:1"},
		}}
	})
	if dev.polls.Load() == 0 {
		t.Error("the suite never polled the device")
	}
}

func TestADeviceThatStaysStoppedNeverPlayedTheStream(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		polls := 0
		poll := func(context.Context) (string, error) {
			polls++
			return "STOPPED", nil
		}
		err := awaitTransportEnd(t.Context(), "Living Room", poll)
		if err == nil || !strings.Contains(err.Error(), "refused the stream") {
			t.Fatalf("awaitTransportEnd() = %v, want the device named as having refused the stream", err)
		}
		if polls != idlePolls {
			t.Errorf("polled %d times, want %d", polls, idlePolls)
		}
	})
}

func TestADeviceBufferingBeforeItPlaysIsNotRefusing(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		script := []string{"STOPPED", "STOPPED", "TRANSITIONING", "STOPPED", "PLAYING", "STOPPED"}
		i := 0
		poll := func(context.Context) (string, error) {
			state := script[i]
			i++
			return state, nil
		}
		if err := awaitTransportEnd(t.Context(), "Living Room", poll); err != nil || i != len(script) {
			t.Fatalf("awaitTransportEnd() = %v after %d polls, want a clean end once it played and stopped", err, i)
		}
	})
}
