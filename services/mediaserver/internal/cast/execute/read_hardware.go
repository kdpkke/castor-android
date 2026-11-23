package execute

import (
	"slices"
	"strings"
)

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
		erring, ok := erringEncoder(line)
		return ok && erring == name
	})
}

// demuxFailed reports an explicit input failure during a remux. An unexplained
// hardware exit can be retried in software, but changing encoder cannot repair this input.
func demuxFailed(lines []string) bool {
	return slices.ContainsFunc(lines, func(line string) bool {
		return strings.HasPrefix(line, "[in#") && strings.Contains(line, "] [error] Error during demuxing")
	})
}

// erringEncoder is the encoder whose own context wrote line as an error: "[enc:name @ …]" or a leading "[name @ …]".
func erringEncoder(line string) (string, bool) {
	if !strings.Contains(line, "] [error] ") && !strings.Contains(line, "] [fatal] ") {
		return "", false
	}
	if _, after, ok := strings.Cut(line, "[enc:"); ok {
		name, _, ok := strings.Cut(after, " @")
		return name, ok
	}
	if rest, ok := strings.CutPrefix(line, "["); ok {
		name, _, ok := strings.Cut(rest, " @")
		return name, ok
	}
	return "", false
}
