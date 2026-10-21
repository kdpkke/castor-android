package follow

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"strings"

	"github.com/stupside/castor/services/mediaserver/internal/media"
	"github.com/stupside/castor/services/mediaserver/internal/source/timeline"
)

// segment serves one listed segment: TS or WebM as the origin sent it, fMP4 as MPEG-TS, each decrypted first.
func (f *feed) segment(w http.ResponseWriter, r *http.Request) {
	sequence, err := strconv.ParseInt(strings.TrimSuffix(r.PathValue("sequence"), repackagedExtension), 10, 64)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	if !servable(w, r) {
		return
	}
	f.mu.Lock()
	s, listed := f.ledger.Entry(sequence)
	repackaging := f.repackaging != nil && *f.repackaging
	f.mu.Unlock()
	// Trimmed: the reader skips it as expired, which is what it is.
	if !listed {
		http.NotFound(w, r)
		return
	}
	ctx := r.Context()
	body, err := f.fetched(ctx, sequence, s)
	if err != nil {
		failed(w, err)
		return
	}
	if s.Map != nil && repackaging {
		f.repackaged(ctx, w, s, body)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	_, _ = w.Write(body)
}

// repackaged serves an fMP4 fragment as MPEG-TS, its init section ahead of it.
func (f *feed) repackaged(ctx context.Context, w http.ResponseWriter, s timeline.Segment, fragment []byte) {
	carries, err := f.carries(ctx, *s.Map)
	if err == nil && (!carries || (s.Key != timeline.Key{} && !f.decoder.ConsumesKey(s.Key))) {
		// The playlist already names it as MPEG-TS, so a segment that cannot become one is a loss to report.
		err = errors.New("the segment's tracks or encryption cannot be carried as MPEG-TS")
	}
	var init []byte
	if err == nil {
		init, err = f.initBytes(ctx, *s.Map)
	}
	if err != nil {
		slog.WarnContext(ctx, "castor could not serve a segment", "input", f.name, "uri", s.URI, "error", err)
		failed(w, err)
		return
	}
	w.Header().Set("Content-Type", media.MPEGTS)
	// The output size is known only after repackaging; hold it on disk so the
	// reader receives a complete, length-delimited response or an error status.
	file, err := os.CreateTemp("", "castor-hls-segment-*.ts")
	if err != nil {
		failed(w, err)
		return
	}
	defer func() {
		_ = file.Close()
		_ = os.Remove(file.Name())
	}()
	if err := f.repackage(ctx, io.MultiReader(bytes.NewReader(init), bytes.NewReader(fragment)), file); err != nil {
		if ctx.Err() == nil {
			slog.WarnContext(ctx, "castor could not repackage a segment", "input", f.name, "uri", s.URI, "error", err)
			failed(w, err)
		}
		return
	}
	info, err := file.Stat()
	if err == nil {
		_, err = file.Seek(0, io.SeekStart)
	}
	if err != nil {
		failed(w, err)
		return
	}
	w.Header().Set("Content-Length", strconv.FormatInt(info.Size(), 10))
	_, _ = io.Copy(w, file)
}

// init serves an init section the playlist names, decrypted when the origin encrypted it whole.
func (f *feed) init(w http.ResponseWriter, r *http.Request) {
	if !servable(w, r) {
		return
	}
	m, ok := f.inits.lookup(r.PathValue("id"))
	if !ok {
		http.NotFound(w, r)
		return
	}
	b, err := f.initBytes(r.Context(), m)
	if err != nil {
		failed(w, err)
		return
	}
	w.Header().Set("Content-Type", media.MP4)
	w.Header().Set("Content-Length", strconv.Itoa(len(b)))
	_, _ = w.Write(b)
}

// key relays a key the reader decrypts with itself.
func (f *feed) key(w http.ResponseWriter, r *http.Request) {
	if !servable(w, r) {
		return
	}
	uri, ok := f.keys.lookup(r.PathValue("id"))
	if !ok {
		http.NotFound(w, r)
		return
	}
	b, err := f.keyBytes(r.Context(), uri)
	if err != nil {
		failed(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(len(b)))
	_, _ = w.Write(b)
}

// servable answers what castor never reads the origin for: a HEAD, and a resume partway into a resource it builds afresh.
func servable(w http.ResponseWriter, r *http.Request) bool {
	if r.Method == http.MethodHead {
		w.WriteHeader(http.StatusOK)
		return false
	}
	if rng := r.Header.Get("Range"); rng != "" && !strings.HasPrefix(rng, "bytes=0-") {
		http.Error(w, "castor serves each resource whole", http.StatusRequestedRangeNotSatisfiable)
		return false
	}
	return true
}

// failed answers with the origin's own refusal, and a bad gateway for anything the origin never said.
func failed(w http.ResponseWriter, err error) {
	status := http.StatusBadGateway
	if f, ok := errors.AsType[*timeline.Failure](err); ok && f.Status >= http.StatusBadRequest {
		status = f.Status
	}
	http.Error(w, err.Error(), status)
}
