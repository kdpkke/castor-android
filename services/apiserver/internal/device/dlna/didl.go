package dlna

import (
	"cmp"
	"encoding/xml"
	"fmt"
	"net/url"

	mediav1 "github.com/stupside/castor/gen/castor/media/v1"
	castorv1 "github.com/stupside/castor/gen/castor/v1"
)

// captionKinds names a caption format the way DLNA renderers do, in a protocolInfo's text/ type and sec:type.
var captionKinds = map[mediav1.CaptionFormat]string{
	mediav1.CaptionFormat_CAPTION_FORMAT_SRT: "srt",
	mediav1.CaptionFormat_CAPTION_FORMAT_VTT: "vtt",
}

// servedHeaders are the headers a DLNA device expects on a response its sink entry describes.
func servedHeaders(entry sinkEntry) map[string]string {
	headers := map[string]string{
		"Connection":            "close",
		"Accept-Ranges":         "none",
		"transferMode.dlna.org": "Streaming",
	}
	if entry.features != "*" {
		headers["contentFeatures.dlna.org"] = entry.features
	}
	return headers
}

type didlLite struct {
	XMLName xml.Name `xml:"DIDL-Lite"`
	XMLNS   string   `xml:"xmlns,attr"`
	DC      string   `xml:"xmlns:dc,attr"`
	UPnP    string   `xml:"xmlns:upnp,attr"`
	SEC     string   `xml:"xmlns:sec,attr,omitempty"`
	Item    didlItem `xml:"item"`
}

type didlItem struct {
	ID          string    `xml:"id,attr"`
	ParentID    string    `xml:"parentID,attr"`
	Restricted  string    `xml:"restricted,attr"`
	Title       string    `xml:"dc:title"`
	Description string    `xml:"dc:description,omitempty"`
	Date        string    `xml:"dc:date,omitempty"`
	AlbumArtURI string    `xml:"upnp:albumArtURI,omitempty"`
	Class       string    `xml:"upnp:class"`
	Res         []didlRes `xml:"res"`
	// Samsung renderers read the caption from this element rather than a second res.
	CaptionInfoEx *secCaption `xml:"sec:CaptionInfoEx"`
}

type secCaption struct {
	Type  string `xml:"sec:type,attr"`
	Value string `xml:",chardata"`
}

type didlRes struct {
	ProtocolInfo string `xml:"protocolInfo,attr"`
	Value        string `xml:",chardata"`
}

// buildDIDLMetadata returns the DIDL-Lite XML announcing streamURL as the device's own sink entry describes it.
// Its caption, when there is one, is announced both as a second res and as Samsung's CaptionInfoEx.
func buildDIDLMetadata(streamURL *url.URL, entry sinkEntry, caption *mediav1.DeviceCommand_Caption, info *castorv1.MediaInfo) (string, error) {
	item := didlLite{
		XMLNS: "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/",
		DC:    "http://purl.org/dc/elements/1.1/",
		UPnP:  "urn:schemas-upnp-org:metadata-1-0/upnp/",
		Item: didlItem{
			ID:          "0",
			ParentID:    "-1",
			Restricted:  "1",
			Title:       cmp.Or(info.GetTitle(), "Castor Stream"),
			Description: info.GetSynopsis(),
			Date:        info.GetReleaseDate(),
			AlbumArtURI: info.GetImageUrl(),
			Class:       "object.item.videoItem",
			Res: []didlRes{{
				ProtocolInfo: fmt.Sprintf("http-get:*:%s:%s", entry.mime, entry.features),
				Value:        streamURL.String(),
			}},
		},
	}
	if caption != nil {
		kind := captionKinds[caption.GetFormat()]
		item.SEC = "http://www.sec.co.kr/"
		item.Item.Res = append(item.Item.Res, didlRes{ProtocolInfo: "http-get:*:text/" + kind + ":*", Value: caption.GetUrl()})
		item.Item.CaptionInfoEx = &secCaption{Type: kind, Value: caption.GetUrl()}
	}

	data, err := xml.Marshal(item)
	if err != nil {
		return "", fmt.Errorf("marshaling DIDL-Lite: %w", err)
	}
	return string(data), nil
}
