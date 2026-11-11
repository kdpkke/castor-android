package extract

// A master belongs to its player even when several loaded levels describe it.
// Query-bearing renditions keep exact identity; a path-only match must be unique.
func matchPlayer(raw string, documents []playerPlaylist) (playerPlaylist, bool) {
	for _, exact := range []bool{true, false} {
		matches := make(map[[2]string]playerPlaylist)
		for _, document := range documents {
			match := func(value string) bool {
				return raw == value || !exact && samePath(raw, value)
			}
			if match(document.Source) {
				document.URL = document.Source
			} else if !match(document.URL) {
				continue
			}
			matches[[2]string{document.Source, document.URL}] = document
		}
		if len(matches) == 1 {
			for _, document := range matches {
				return document, true
			}
		}
		if len(matches) > 1 {
			break
		}
	}
	return playerPlaylist{}, false
}
