// Package browserstream serves playlists prepared by a retained page's player.
package browserstream

import _ "embed"

//go:embed assets/castor-browser/player.js
var PlayerScript string
