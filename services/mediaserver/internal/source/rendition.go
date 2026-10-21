package source

import (
	"net/url"

	"github.com/stupside/castor/services/mediaserver/internal/media"
)

// Rendition is one version of a program a source offered, as the source described it.
type Rendition struct {
	URL *url.URL

	// Representation names the rung inside a manifest that publishes its ladder behind one URL.
	Representation string

	// AudioURL is the companion audio rendition for this rung.
	AudioURL *url.URL
	// AudioLanguage is the language of the selected audio track, when declared.
	AudioLanguage string

	// Captions are the text renditions the source published beside this rung.
	Captions []Caption

	// Bitrate is the rate the source declared, 0 when it declared none.
	Bitrate media.Bitrate

	// Height is the declared display height, 0 when the source omitted it.
	Height int

	// Declared is the codec envelope the source declared for this rung.
	Declared *media.Declaration
}

// Caption is one text rendition a source published: a WebVTT playlist, in a language.
type Caption struct {
	URL      *url.URL
	Language string
	Name     string
	Default  bool
	Forced   bool
}

// CompareBool orders false before true.
func CompareBool(a, b bool) int {
	switch {
	case a == b:
		return 0
	case b:
		return -1
	}
	return 1
}
