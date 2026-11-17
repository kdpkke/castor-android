package ffmpeg

import "slices"

// Android extension: lets a cast tell a hardware encoder's own error lines from the source's (execute/read_hardware.go).

// HardwareEncoder reports whether name is a hardware encoder castor knows.
func HardwareEncoder(name string) bool {
	return slices.ContainsFunc(known, func(e Encoder) bool { return e.Hardware && e.Name == name })
}
