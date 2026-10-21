package media

// Axes is which halves of a program have to be re-encoded rather than copied.
type Axes struct {
	Video bool
	Audio bool
}

func (a Axes) Any() bool { return a.Video || a.Audio }

// Or is both demands to re-encode at once, and neither overrules the other.
func (a Axes) Or(b Axes) Axes { return Axes{Video: a.Video || b.Video, Audio: a.Audio || b.Audio} }

func (a Axes) String() string {
	switch {
	case a.Video && a.Audio:
		return "video and audio"
	case a.Video:
		return "video"
	case a.Audio:
		return "audio"
	default:
		return "neither axis"
	}
}
