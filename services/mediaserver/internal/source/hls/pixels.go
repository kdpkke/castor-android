package hls

import (
	"bytes"
	"compress/gzip"
	"encoding/binary"
	"fmt"
	"image/color"
	"image/png"
	"io"
)

const pixelLimit = 32 << 20

// unwrapPixels reads the TIKTIKPX envelope: RGB bytes hold a magic, a big-endian
// gzip length, and gzipped MPEG-TS. Alpha and trailing pixel padding carry no data.
func unwrapPixels(body []byte) ([]byte, bool, error) {
	if !bytes.HasPrefix(body, []byte("\x89PNG\r\n\x1a\n")) {
		return body, false, nil
	}
	cfg, err := png.DecodeConfig(bytes.NewReader(body))
	if err != nil {
		return nil, false, fmt.Errorf("reading PNG segment: %w", err)
	}
	// These are the standard decoder's 8-bit RGB and RGBA models.
	if cfg.ColorModel != color.RGBAModel && cfg.ColorModel != color.NRGBAModel {
		return body, false, nil
	}
	// Bound the decoder's four-byte pixels before allocating the image.
	pixels := uint64(cfg.Width) * uint64(cfg.Height)
	if pixels > pixelLimit/4 {
		return nil, false, fmt.Errorf("PNG segment exceeds %d decoded bytes", pixelLimit)
	}
	if pixels < 4 {
		return body, false, nil
	}
	img, err := png.Decode(bytes.NewReader(body))
	if err != nil {
		return nil, false, fmt.Errorf("decoding PNG segment: %w", err)
	}
	// Extract only the header and declared payload, without copying pixel padding.
	rgb := make([]byte, 0, 12)
	want := 12
	for y := 0; y < cfg.Height && len(rgb) < want; y++ {
		for x := 0; x < cfg.Width && len(rgb) < want; x++ {
			c := color.NRGBAModel.Convert(img.At(x, y)).(color.NRGBA)
			rgb = append(rgb, c.R, c.G, c.B)
			if len(rgb) == 12 {
				if string(rgb[:8]) != "TIKTIKPX" {
					return body, false, nil
				}
				n := uint64(binary.BigEndian.Uint32(rgb[8:12]))
				if n == 0 || n > pixels*3-12 {
					return nil, false, fmt.Errorf("invalid PNG segment payload length %d", n)
				}
				want += int(n)
			}
		}
	}
	z, err := gzip.NewReader(bytes.NewReader(rgb[12:want]))
	if err != nil {
		return nil, false, fmt.Errorf("opening PNG segment payload: %w", err)
	}
	defer z.Close()
	ts, err := io.ReadAll(io.LimitReader(z, pixelLimit+1))
	if err != nil {
		return nil, false, fmt.Errorf("expanding PNG segment payload: %w", err)
	}
	if len(ts) > pixelLimit {
		return nil, false, fmt.Errorf("PNG segment payload exceeds %d bytes", pixelLimit)
	}
	if len(ts) < 3*188 || len(ts)%188 != 0 {
		return nil, false, fmt.Errorf("PNG segment payload is not packet-aligned MPEG-TS")
	}
	for i := 0; i < len(ts); i += 188 {
		if ts[i] != 0x47 {
			return nil, false, fmt.Errorf("PNG segment payload loses MPEG-TS sync at byte %d", i)
		}
	}
	return ts, true, nil
}
