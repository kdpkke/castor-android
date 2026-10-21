package execute

import (
	"log/slog"
	"slices"
	"time"

	"github.com/stupside/castor/services/mediaserver/internal/captions"
	"github.com/stupside/castor/services/mediaserver/internal/cast/output"
	"github.com/stupside/castor/services/mediaserver/internal/media"
	"github.com/stupside/castor/services/mediaserver/internal/source"
)

// Captions is how a cast shows the text the source published, nil for a cast that wants none.
type Captions struct {
	Languages []string
	Client    source.Client
}

// shownCaptions fetches the caption the cast wants from what the source published and serves it for the device.
// A caption is a courtesy: whatever stops it is logged and the cast plays without.
func (s *preparation) shownCaptions() []media.Caption {
	want := s.Captions
	published := s.attempt.Rendition.Captions
	if want == nil || len(published) == 0 {
		return nil
	}
	format, ok := captionFormat(s.capabilities)
	if !ok {
		slog.InfoContext(s.ctx, "the source publishes captions, but the device shows none")
		return nil
	}
	matches := captions.Matches(published, want.Languages)
	if len(matches) == 0 {
		slog.InfoContext(s.ctx, "the source publishes no caption in the language asked", "published", len(published))
		return nil
	}
	primary, _ := s.attempt.Program.PrimaryInput()
	var origin *time.Duration
	if measured, ok := s.attempt.Program.Measurement(); ok && measured.StartKnown {
		origin = &measured.Start
	}
	for _, pick := range matches {
		vtt, err := captions.FetchAt(s.ctx, want.Client, pick, primary.Headers, origin)
		if err != nil {
			slog.WarnContext(s.ctx, "source caption unavailable, trying the next track", "language", pick.Language, "error", err)
			continue
		}
		name, contentType, body := "captions.vtt", "text/vtt", vtt
		if format == media.CaptionSRT {
			name, contentType, body = "captions.srt", "text/srt", captions.SRT(vtt)
		}
		u, closeStatic, err := output.Static(s.ctx, s.Publisher, name, contentType, body)
		if err != nil {
			slog.WarnContext(s.ctx, "casting without the source's captions", "error", err)
			return nil
		}
		s.releases.publication(closeStatic)
		slog.InfoContext(s.ctx, "showing the source's captions", "language", pick.Language, "format", string(format))
		return []media.Caption{{URL: u, Format: format, Language: pick.Language, Name: pick.Name, Default: true}}
	}
	return nil
}

// captionFormat is the format a device takes captions in, WebVTT before SubRip.
func captionFormat(c media.Capabilities) (media.CaptionFormat, bool) {
	for _, f := range []media.CaptionFormat{media.CaptionVTT, media.CaptionSRT} {
		if slices.ContainsFunc(c.Formats, func(format media.FormatSupport) bool { return slices.Contains(format.Captions, f) }) {
			return f, true
		}
	}
	return "", false
}

// playbackCapabilities keeps sidecar support attached to the codec/transport
// envelope that advertised it. Both handoff and encoding consume this selection.
func (s *preparation) playbackCapabilities() media.Capabilities {
	caps := s.capabilities
	if len(s.shown) == 0 {
		return caps
	}
	caps.Formats = slices.DeleteFunc(slices.Clone(caps.Formats), func(f media.FormatSupport) bool {
		return slices.ContainsFunc(s.shown, func(c media.Caption) bool { return !slices.Contains(f.Captions, c.Format) })
	})
	return caps
}

// nativeOutput asks the installed delivery adapters to carry generated cues.
// Source clock availability is a media fact; protocol eligibility belongs to adapters.
func (s *preparation) nativeOutput(f feed) (output.Selection, bool) {
	if f.buffered == nil || f.buffered.transcript == nil || f.buffered.captionVideoOffset == nil {
		return output.Selection{}, false
	}
	selected, err := s.Outputs.Select(s.playbackCapabilities(), &output.Captions{
		Cues: f.buffered.transcript.Cues(), VideoOffset: *f.buffered.captionVideoOffset,
	})
	return selected, err == nil
}
