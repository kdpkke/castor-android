package fcast

import (
	"context"
	"encoding/json/v2"
	"errors"
	"fmt"
	"net"
	"sync"
	"time"

	mediav1 "github.com/stupside/castor/gen/castor/media/v1"
	"github.com/stupside/castor/services/apiserver/internal/device"
)

// protocolVersion is the FCast version castor speaks; receivers that know v4 downgrade to it.
const protocolVersion = 3

// hlsContainer is how a Play names HLS. Receivers match the name in lower case against a list that spells the
// other common name, application/x-mpegURL, in mixed case: that one never reaches their player, the registered one does.
const hlsContainer = "application/vnd.apple.mpegurl"

// containerName is how a Play names container to a receiver; empty for one FCast has no name for.
func containerName(container mediav1.Container) string {
	if container == mediav1.Container_CONTAINER_HLS {
		return hlsContainer
	}
	return device.MIME(container)
}

// session is one FCast receiver, following the Play it was last handed. Its connection is replaced when a receiver
// that took a Play relaunches, so it is read through link.
type session struct {
	name string
	// announced carries the version the receiver states on connecting.
	announced chan uint64
	// redial connects to the receiver again; nil where the session cannot.
	redial func(context.Context) (net.Conn, error)

	mu      sync.Mutex
	ch      *channel
	closed  bool
	current *playback
}

// link is the connection castor speaks to the receiver on now.
func (s *session) link() *channel {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.ch
}

var _ device.Device = (*session)(nil)

type versionMessage struct {
	Version uint64 `json:"version"`
}

type initialMessage struct {
	AppName string `json:"appName"`
}

type subscribeMessage struct {
	Event struct {
		Type int `json:"type"`
	} `json:"event"`
}

// playMessage leaves out volume, speed and time: a receiver applies whatever it is sent, zero too.
type playMessage struct {
	Container string    `json:"container"`
	URL       string    `json:"url"`
	Metadata  *metadata `json:"metadata,omitempty"`
}

// metadata is the protocol's generic kind; receivers reject one without its type, so the zero is sent.
type metadata struct {
	Type         int    `json:"type"`
	Title        string `json:"title,omitempty"`
	ThumbnailURL string `json:"thumbnailUrl,omitempty"`
}

// open takes conn through the v3 handshake.
func open(ctx context.Context, conn net.Conn, name string) (*session, error) {
	s := &session{name: name, announced: make(chan uint64, 1)}
	s.ch = newChannel(conn, s.observe)
	if err := s.handshake(ctx, s.ch); err != nil {
		_ = s.ch.Close()
		return nil, fmt.Errorf("connecting to fcast receiver: %w", err)
	}
	return s, nil
}

// handshake takes ch through the v3 greeting.
func (s *session) handshake(ctx context.Context, ch *channel) error {
	ctx, cancel := context.WithTimeoutCause(ctx, answerWithin, fmt.Errorf("the fcast receiver did not finish the handshake within %s: %w", answerWithin, context.DeadlineExceeded))
	defer cancel()
	// Version goes first: a receiver takes any other opening packet for a v1 sender.
	if err := ch.sendJSON(ctx, opVersion, versionMessage{Version: protocolVersion}); err != nil {
		return err
	}
	var theirs uint64
	select {
	case theirs = <-s.announced:
	case <-ch.gone:
		return errors.New("the receiver closed the connection before announcing its version")
	case <-ctx.Done():
		return context.Cause(ctx)
	}
	if theirs < protocolVersion {
		return fmt.Errorf("the receiver speaks FCast v%d; castor needs v%d", theirs, protocolVersion)
	}
	if err := ch.sendJSON(ctx, opInitial, initialMessage{AppName: "Castor"}); err != nil {
		return err
	}
	// Receivers report a finished item as paused; only this event tells its end.
	var subscribe subscribeMessage
	subscribe.Event.Type = eventMediaItemEnd
	return ch.sendJSON(ctx, opSubscribeEvent, subscribe)
}

// reconnect waits for a receiver that closed the connection after echoing a Play to come back, as an LG C1's does: it
// relaunches its player, refuses connections for some seconds, then reports the media it plays to every sender.
func (s *session) reconnect(ctx context.Context) error {
	if s.redial == nil {
		return errors.New("this session cannot connect again")
	}
	retry := time.NewTicker(reconnectEvery)
	defer retry.Stop()
	for {
		conn, err := s.redial(ctx)
		if err == nil {
			ch := newChannel(conn, s.observe)
			if err = s.handshake(ctx, ch); err == nil {
				s.mu.Lock()
				if s.closed {
					s.mu.Unlock()
					_ = ch.Close()
					return errors.New("the session was closed")
				}
				stale := s.ch
				s.ch = ch
				s.mu.Unlock()
				_ = stale.Close()
				return nil
			}
			_ = ch.Close()
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("%w (last attempt: %v)", context.Cause(ctx), err)
		case <-retry.C:
		}
	}
}

// observe routes what the receiver says, from the reader's goroutine.
func (s *session) observe(op opcode, body []byte) {
	if op == opVersion {
		var v versionMessage
		if json.Unmarshal(body, &v) == nil {
			select {
			case s.announced <- v.Version:
			default:
			}
		}
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if p := s.current; p != nil && p.observe(op, body) {
		time.AfterFunc(idleSettle, func() { s.settle(p) })
	}
}

// settle ends p as stopped if it is still idle, no error having followed.
func (s *session) settle(p *playback) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if p.idle {
		p.ending.finish(nil)
	}
}

// Play returns once the receiver plays the media it echoed back, so one it refuses or never starts fails the
// hand-off, which a Native cast falls back from. A receiver that closes the connection once handed the media refuses
// it too: an LG C1's did while the TV went on playing the file outside it, where castor can neither follow nor control
// it, so the fallback reaches the receiver's own player. A connection that closed before the Play went out is gone.
func (s *session) Play(ctx context.Context, playback device.Playback) error {
	err := s.play(ctx, playback)
	if err == nil || errors.Is(err, errDroppedAfterPlay) {
		return err
	}
	select {
	case <-s.link().gone:
		return &device.Gone{Device: s.name, Observed: "the FCast connection closed before the receiver was handed the media", Err: err}
	default:
		return err
	}
}

// errDroppedAfterPlay is a receiver that closed the connection once handed the media, without playing it here.
var errDroppedAfterPlay = errors.New("the fcast receiver closed the connection once handed the media, without playing it where castor can follow")

func (s *session) play(ctx context.Context, playback device.Playback) error {
	select {
	case <-s.link().gone:
		return errors.New("the fcast connection is closed")
	default:
	}
	stream, info := playback.URL.String(), playback.Info
	container := containerName(playback.Container())
	if container == "" {
		return fmt.Errorf("fcast has no name for container %v", playback.Container())
	}
	message := playMessage{Container: container, URL: stream}
	if info.GetTitle() != "" || info.GetImageUrl() != "" {
		message.Metadata = &metadata{Title: info.GetTitle(), ThumbnailURL: info.GetImageUrl()}
	}
	mine := newPlayback(stream)
	s.mu.Lock()
	s.current = mine
	s.mu.Unlock()
	err := s.link().sendJSON(ctx, opPlay, message)
	if err == nil {
		err = s.verdict(ctx, mine)
	}
	if err != nil {
		s.mu.Lock()
		// A Play begun since owns the watch now; this one's failure is not its to clear.
		if s.current == mine {
			s.current = nil
		}
		s.mu.Unlock()
		return fmt.Errorf("starting fcast playback: %w", err)
	}
	return nil
}

func (s *session) verdict(ctx context.Context, p *playback) error {
	ctx, cancel := context.WithTimeoutCause(ctx, startupTimeout, fmt.Errorf("the fcast receiver did not take the media and start playing it within %s: %w", startupTimeout, context.DeadlineExceeded))
	defer cancel()
	for {
		ch := s.link()
		select {
		case err := <-p.verdict:
			return err
		case <-ch.gone:
			if !s.echoed(p) {
				return errDroppedAfterPlay
			}
			// The receiver took the Play and dropped the connection: it may be relaunching to play it.
			if err := s.reconnect(ctx); err != nil {
				return fmt.Errorf("%w; connecting again: %w", errDroppedAfterPlay, err)
			}
		case <-ctx.Done():
			return context.Cause(ctx)
		}
	}
}

// echoed is whether the receiver echoed p back.
func (s *session) echoed(p *playback) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return p.loaded
}

func (s *session) Pause(ctx context.Context) error { return s.command(ctx, opPause, "pausing") }

func (s *session) Resume(ctx context.Context) error { return s.command(ctx, opResume, "resuming") }

// Stop ends what the receiver plays, whoever handed it.
func (s *session) Stop(ctx context.Context) error { return s.command(ctx, opStop, "stopping") }

func (s *session) command(ctx context.Context, op opcode, doing string) error {
	if err := s.link().send(ctx, op, nil); err != nil {
		return fmt.Errorf("%s the fcast receiver: %w", doing, err)
	}
	return nil
}

// seekMessage moves the receiver's player to time, in seconds.
type seekMessage struct {
	Time float64 `json:"time"`
}

const (
	// A receiver reports its time every second or so; one that has not reported near a seek in this long did not take it.
	seekWithin = 8 * time.Second
	// A player lands on a keyframe near the time asked, which a 2-5 s GOP puts up to a few seconds away.
	seekSlack = 5 * time.Second
)

var _ device.Controller = (*session)(nil)

// Controls are what the receiver obeys for the media it plays: pause, and seeking within it once it plays.
func (s *session) Controls() device.Controls {
	s.mu.Lock()
	defer s.mu.Unlock()
	return device.Controls{Pause: true, Seek: s.current != nil && s.current.active}
}

// Seek moves the receiver's player within the media it plays, returning once it reports a time near at. Castor seeks
// so only an original the receiver fetches itself; a receiver that does not get there leaves castor to prepare the
// media from at instead.
func (s *session) Seek(ctx context.Context, at time.Duration) error {
	s.mu.Lock()
	p := s.current
	var before int
	if p != nil {
		before = p.updates
	}
	s.mu.Unlock()
	if p == nil {
		return device.ErrUnsupportedControl
	}
	ctx, cancel := context.WithTimeout(ctx, seekWithin)
	defer cancel()
	if err := s.link().sendJSON(ctx, opSeek, seekMessage{Time: at.Seconds()}); err != nil {
		return fmt.Errorf("seeking the fcast receiver: %w", err)
	}
	poll := time.NewTicker(200 * time.Millisecond)
	defer poll.Stop()
	for {
		s.mu.Lock()
		// Only a report made after the Seek tells where it landed.
		landed := p.updates > before && p.placed && (p.head.At-at).Abs() <= seekSlack
		s.mu.Unlock()
		if landed {
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("the fcast receiver did not report a time near %s after seeking: %w", at, ctx.Err())
		case <-poll.C:
		}
	}
}

// Position is the last one the receiver reported for the media castor handed it, which it pushes every second or so.
func (s *session) Position(context.Context) (device.Playhead, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if p := s.current; p != nil && p.placed {
		return p.head, nil
	}
	return device.Playhead{}, device.ErrNoPosition
}

func (s *session) Close() error {
	s.mu.Lock()
	s.closed = true
	ch := s.ch
	s.mu.Unlock()
	return ch.Close()
}

// Capabilities is what every FCast receiver app is expected to play; v3 carries no subtitle tracks, so captions are burnt in.
// Receiver apps differ (ExoPlayer on Android TV, a browser on the desktop and on webOS), so a Native cast leaves the
// tracks to the receiver's verdict: Play waits for the receiver to play. Matroska and WebM are named only so an original
// in them can be handed over; castor never serves them.
func (s *session) Capabilities() *mediav1.Capabilities {
	caps := &mediav1.Capabilities{
		Provenance: "FCast v3 receiver family envelopes; receiver app and device limits unverified; Native uses the receiver's playback verdict",
		SelfFetch:  true, NativePlaybackTrial: true,
	}
	containers := []mediav1.ProgressiveSupport_Container{
		mediav1.ProgressiveSupport_CONTAINER_MP4, mediav1.ProgressiveSupport_CONTAINER_MKV, mediav1.ProgressiveSupport_CONTAINER_WEBM,
	}
	for _, audio := range []mediav1.AudioCodec{mediav1.AudioCodec_AUDIO_CODEC_AAC, mediav1.AudioCodec_AUDIO_CODEC_MP3} {
		tracks := &mediav1.FormatSupport_Av{Av: &mediav1.AVSupport{Video: device.H264Support(), Audio: &mediav1.AudioSupport{Codec: audio, MaxChannels: 2}}}
		caps.Formats = append(caps.Formats, &mediav1.FormatSupport{Transport: &mediav1.FormatSupport_Hls{Hls: &mediav1.HLSSupport{}}, Tracks: tracks})
		for _, container := range containers {
			caps.Formats = append(caps.Formats, &mediav1.FormatSupport{
				Transport: &mediav1.FormatSupport_Progressive{Progressive: &mediav1.ProgressiveSupport{Container: container}}, Tracks: tracks,
			})
		}
	}
	return caps
}
