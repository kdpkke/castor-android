package media

// Declaration is the track envelope a manifest states. It carries no probe
// completion, timestamps, or evidence about the bytes a reader opened.
type Declaration struct {
	VideoCodec    Codec
	VideoProfile  Profile
	VideoHeight   int
	VideoBitDepth int
	VideoLevel    int
	AudioCodec    Codec
}

// TrackRequirements says which tracks an artifact must contain. A false field
// imposes no requirement; it does not assert that the track is absent.
type TrackRequirements struct {
	Video bool
	Audio bool
}

func (r TrackRequirements) Or(other TrackRequirements) TrackRequirements {
	return TrackRequirements{Video: r.Video || other.Video, Audio: r.Audio || other.Audio}
}

func (p ProbeInfo) RequiredTracks() TrackRequirements {
	return TrackRequirements{Video: p.HasVideo(), Audio: p.HasAudio()}
}

// Observation retains the exact resources and tracks that a successful probe
// inspected. Its snapshot and facts are private and copied at the boundary.
type Observation struct {
	bindings Program
	info     ProbeInfo
}

type programDeclaration struct {
	bindings Program
	tracks   Declaration
}

func Observe(program Program, info ProbeInfo) Observation {
	program.observation, program.declaration = nil, nil
	return Observation{bindings: program.Clone(), info: info.Clone()}
}

// For refuses facts about another selection, including changed replay headers.
func (o Observation) For(program Program) (ProbeInfo, bool) {
	if len(o.bindings.Inputs) == 0 || !o.bindings.SameBindings(program) {
		return ProbeInfo{}, false
	}
	return o.info.Clone(), true
}

func (p *Program) Declaration() (Declaration, bool) {
	if p.declaration == nil || !p.declaration.bindings.SameBindings(*p) {
		return Declaration{}, false
	}
	return p.declaration.tracks, true
}

func (p *Program) SetDeclaration(declaration Declaration) {
	bindings := *p
	bindings.observation, bindings.declaration = nil, nil
	p.declaration = &programDeclaration{bindings: bindings.Clone(), tracks: declaration}
}

// RequiredTracks combines selected mandatory tracks with positive source
// evidence. A declaration can require a track, but cannot establish absence.
func (p *Program) RequiredTracks() TrackRequirements {
	var required TrackRequirements
	for _, track := range p.Tracks {
		if !track.Optional {
			required.Video = required.Video || track.Kind == TrackVideo
			required.Audio = required.Audio || track.Kind == TrackAudio
		}
	}
	if declared, ok := p.Declaration(); ok {
		required = required.Or(TrackRequirements{Video: declared.VideoCodec != "", Audio: declared.AudioCodec != ""})
	}
	if observed, ok := p.Measurement(); ok {
		required = required.Or(observed.RequiredTracks())
	}
	return required
}
