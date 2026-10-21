package hls

import (
	"fmt"
	"math"
	"os"
	"path"

	"github.com/Eyevinn/mp4ff/mp4"
)

// captionMedia uses the muxer's actual timestamps, including B-frame
// composition offsets. Segment durations are not assumed to equal hls_time:
// stream copying cuts at the source's keyframes.
type captionMediaTiming struct {
	Start, End float64
	VideoStart *float64 // first video presentation timestamp, absent in audio-only fragments
	Codecs     string
}

func captionMedia(root *os.Root, initName, segmentName string) (captionMediaTiming, error) {
	if path.Base(initName) != initName || path.Base(segmentName) != segmentName {
		return captionMediaTiming{}, fmt.Errorf("invalid generated media path")
	}
	initFile, err := root.Open(initName)
	if err != nil {
		return captionMediaTiming{}, err
	}
	init, err := mp4.DecodeFile(initFile, mp4.WithDecodeMode(mp4.DecModeLazyMdat))
	initFile.Close()
	if err != nil {
		return captionMediaTiming{}, err
	}
	if init.Moov == nil {
		return captionMediaTiming{}, fmt.Errorf("missing movie metadata")
	}
	codecs, err := renditionCodecs(init.Moov)
	if err != nil {
		return captionMediaTiming{}, err
	}
	fragmentFile, err := root.Open(segmentName)
	if err != nil {
		return captionMediaTiming{}, err
	}
	fragment, err := mp4.DecodeFile(fragmentFile, mp4.WithDecodeMode(mp4.DecModeLazyMdat))
	fragmentFile.Close()
	if err != nil {
		return captionMediaTiming{}, err
	}

	// Subtitle intervals cover all audible media, including speech before the
	// picture starts or after it ends. Keep the video anchor separately.
	result := captionMediaTiming{Start: math.Inf(1), End: math.Inf(-1), Codecs: codecs}
	for _, kind := range []string{"vide", "soun"} {
		for _, track := range init.Moov.Traks {
			if track.Tkhd == nil || track.Mdia == nil || track.Mdia.Hdlr == nil || track.Mdia.Hdlr.HandlerType != kind || track.Mdia.Mdhd == nil || track.Mdia.Mdhd.Timescale == 0 {
				continue
			}

			var trex *mp4.TrexBox
			if init.Moov.Mvex != nil {
				trex, _ = init.Moov.Mvex.GetTrex(track.Tkhd.TrackID)
			}
			first, last := math.Inf(1), math.Inf(-1)
			scale := float64(track.Mdia.Mdhd.Timescale)
			for _, s := range fragment.Segments {
				for _, f := range s.Fragments {
					for _, traf := range f.Moof.Trafs {
						if traf.Tfhd == nil || traf.Tfhd.TrackID != track.Tkhd.TrackID || traf.Tfdt == nil {
							continue
						}
						decodeTime := int64(traf.Tfdt.BaseMediaDecodeTime())
						for _, trun := range traf.Truns {
							trun.AddSampleDefaultValues(traf.Tfhd, trex)
							for _, sample := range trun.Samples {
								if sample.Dur == 0 {
									return captionMediaTiming{}, fmt.Errorf("missing media sample duration")
								}
								pts := decodeTime + int64(sample.CompositionTimeOffset)
								first = min(first, float64(pts)/scale)
								last = max(last, float64(pts+int64(sample.Dur))/scale)
								decodeTime += int64(sample.Dur)
							}
						}
					}
				}
			}
			if !math.IsInf(first, 1) {
				// fMP4 may store a delayed track on a zero-based media clock;
				// its edit list restores that delay on the movie timeline.
				shift := 0.0
				if track.Edts != nil {
					if init.Moov.Mvhd == nil || init.Moov.Mvhd.Timescale == 0 {
						return captionMediaTiming{}, fmt.Errorf("missing movie clock for edit list")
					}
					for _, list := range track.Edts.Elst {
						for _, edit := range list.Entries {
							if edit.MediaRateInteger != 1 || edit.MediaRateFraction != 0 {
								return captionMediaTiming{}, fmt.Errorf("unsupported media edit rate")
							}
							if edit.MediaTime == -1 {
								shift += float64(edit.SegmentDuration) / float64(init.Moov.Mvhd.Timescale)
							} else {
								shift -= float64(edit.MediaTime) / scale
							}
						}
					}
				}
				result.Start = min(result.Start, first+shift)
				result.End = max(result.End, last+shift)
				if kind == "vide" {
					start := first + shift
					if result.VideoStart == nil || start < *result.VideoStart {
						result.VideoStart = &start
					}
				}
			}

		}
	}
	if math.IsInf(result.Start, 1) {
		return captionMediaTiming{}, fmt.Errorf("missing fragment media timestamp")
	}
	return result, nil
}
