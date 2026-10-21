package device

import (
	"google.golang.org/protobuf/proto"

	mediav1 "github.com/stupside/castor/gen/castor/media/v1"
)

// MIME is how a device protocol names container, as the contract states it; empty for one it does not know.
func MIME(container mediav1.Container) string {
	v := container.Descriptor().Values().ByNumber(container.Number())
	if v == nil {
		return ""
	}
	return proto.GetExtension(v.Options(), mediav1.E_Mime).(string)
}

// CaptionMIME is the content type a caption track is fetched as; empty for a format the contract does not name.
func CaptionMIME(f mediav1.CaptionFormat) string {
	switch f {
	case mediav1.CaptionFormat_CAPTION_FORMAT_VTT:
		return "text/vtt"
	case mediav1.CaptionFormat_CAPTION_FORMAT_SRT:
		return "application/x-subrip"
	}
	return ""
}

// H264Support is the common HD H.264 envelope advertised by device families.
func H264Support() *mediav1.VideoSupport {
	return &mediav1.VideoSupport{Codec: &mediav1.VideoSupport_H264{H264: &mediav1.H264Support{
		Profiles: []mediav1.H264Support_Profile{
			mediav1.H264Support_PROFILE_CONSTRAINED_BASELINE,
			mediav1.H264Support_PROFILE_BASELINE,
			mediav1.H264Support_PROFILE_MAIN,
			mediav1.H264Support_PROFILE_HIGH,
		},
		MaxLevel: 42,
	}}}
}
