package execute

import (
	"slices"
	"strings"
)

// Android extension: a read that makes its picture runs the source and the encoder in one ffmpeg, whose exit alone
// cannot say which of them failed; the encoder's own error lines can. A phone's hardware encoder refuses pictures
// software makes, such as a small one, so a read it failed is made again in software instead of ending the cast.

// hardwareFailed is a read whose hardware video encoder logged its own failure.
func (p *pull) hardwareFailed() bool {
	t := p.tail()
	encode, ok := t.floor.Video.Encode()
	if !ok || !encode.Encoder.Hardware {
		return false
	}
	return failedItself(encode.Encoder.Name, t.proc.Evidence().Lines)
}

// failedItself is a log with errors from the encoder named name, not from the input it read or the muxer it fed.
func failedItself(name string, lines []string) bool {
	return slices.ContainsFunc(lines, func(line string) bool {
		return (strings.Contains(line, "] [error] ") || strings.Contains(line, "] [fatal] ")) &&
			(strings.HasPrefix(line, "["+name+" @") || strings.Contains(line, "[enc:"+name+" @"))
	})
}
