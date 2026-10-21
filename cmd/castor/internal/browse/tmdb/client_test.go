package tmdb

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strings"
	"sync"
	"testing"
)

// Search uses one url.Values for two requests; get must not mutate caller's map.
func TestGetTreatsCallerValuesAsReadOnly(t *testing.T) {
	var mu sync.Mutex
	var keys []string
	srv := httptest.NewTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		keys = append(keys, r.URL.Query().Get("api_key"))
		mu.Unlock()
		_, _ = w.Write([]byte(`{"results":[{"id":1,"title":"Dune"}]}`))
	}))
	hc := srv.Client()
	c := &Client{apiKey: "secret", base: srv.URL, http: hc}

	q := url.Values{"query": {"dune"}}
	var out struct{}
	if err := c.get(t.Context(), "/search/movie", q, &out); err != nil {
		t.Fatal(err)
	}
	if _, mutated := q["api_key"]; mutated {
		t.Fatal("get wrote api_key into the caller's values")
	}

	// Race detector proves Search goroutines don't collide on shared map.
	res, err := c.Search(t.Context(), "dune")
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 2 {
		t.Fatalf("Search returned %d rows, want one per media type", len(res))
	}
	mu.Lock()
	defer mu.Unlock()
	for i, k := range keys {
		if k != "secret" {
			t.Fatalf("request %d carried api_key %q", i, k)
		}
	}
}

type failingTransport struct{}

func (failingTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return nil, errors.New("connection refused")
}

// Errors reach the TUI status line, so none may quote the keyed request URL.
func TestErrorsNeverCarryTheAPIKey(t *testing.T) {
	const key = "s3cr3t-api-key"
	c := New(Config{APIKey: key})
	c.http.Transport = failingTransport{}

	_, searchErr := c.Search(t.Context(), "dune")
	_, detailsErr := c.Details(t.Context(), MediaMovie, 42)
	_, posterErr := c.Poster(t.Context(), "/p.jpg")
	for name, err := range map[string]error{"search": searchErr, "details": detailsErr, "poster": posterErr} {
		if err == nil {
			t.Fatalf("%s: want an error from a failing transport", name)
		}
		if strings.Contains(err.Error(), key) {
			t.Errorf("%s error leaks the api key: %v", name, err)
		}
	}
}

func TestPosterStreamsTheImageAndRejectsMissingOnes(t *testing.T) {
	srv := httptest.NewTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/w500/p.jpg" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write([]byte("jpeg bytes"))
	}))
	c := New(Config{APIKey: "secret"})
	c.http = srv.Client()
	c.images = srv.URL + "/"

	body, err := c.Poster(t.Context(), "/p.jpg")
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(body)
	_ = body.Close()
	if err != nil || string(got) != "jpeg bytes" {
		t.Fatalf("poster body = %q, %v", got, err)
	}

	if _, err := c.Poster(t.Context(), "/gone.jpg"); err == nil {
		t.Fatal("a 404 poster should be an error, not an empty image")
	}
}

// TMDB's payloads, as it sends them, decode into every field the browser reads.
func TestTMDBPayloadsDecodeAsTMDBSendsThem(t *testing.T) {
	bodies := map[string]string{
		"/movie/603":           `{"adult":false,"backdrop_path":"/b.jpg","belongs_to_collection":{"id":2344,"name":"The Matrix Collection"},"budget":63000000,"genres":[{"id":28,"name":"Action"},{"id":878,"name":"Science Fiction"}],"homepage":"","id":603,"imdb_id":"tt0133093","original_language":"en","original_title":"The Matrix","overview":"A hacker learns the truth.","popularity":91.2,"poster_path":"/p.jpg","release_date":"1999-03-31","revenue":463517383,"runtime":136,"status":"Released","tagline":"Welcome to the Real World.","title":"The Matrix","video":false,"vote_average":8.2,"vote_count":26000,"credits":{"cast":[{"adult":false,"gender":2,"id":6384,"known_for_department":"Acting","name":"Keanu Reeves","character":"Neo","credit_id":"52fe425bc3a36847f80181c1","order":0},{"name":"Laurence Fishburne","order":1}],"crew":[]}}`,
		"/tv/2316":             `{"id":2316,"name":"The Office","episode_run_time":[22],"genres":[{"id":35,"name":"Comedy"}],"tagline":"","seasons":[{"air_date":"2005-03-24","episode_count":6,"id":7240,"name":"Season 1","overview":"","poster_path":"/s1.jpg","season_number":1,"vote_average":7.6}]}`,
		"/tv/2316/season/1":    `{"_id":"5256c89f19c2956ff6046d47","air_date":"2005-03-24","episodes":[{"air_date":"2005-03-24","episode_number":1,"episode_type":"standard","id":397645,"name":"Pilot","overview":"The premiere.","runtime":23,"season_number":1,"show_id":2316,"still_path":"/x.jpg","vote_average":7.4,"vote_count":200,"crew":[],"guest_stars":[]}],"name":"Season 1","id":7240,"season_number":1}`,
		"/discover/movie":      `{"page":1,"results":[{"adult":false,"backdrop_path":"/b.jpg","genre_ids":[28,878],"id":603,"original_language":"en","original_title":"The Matrix","overview":"A hacker learns the truth.","popularity":91.2,"poster_path":"/p.jpg","release_date":"1999-03-31","title":"The Matrix","video":false,"vote_average":8.2,"vote_count":26000}],"total_pages":42,"total_results":840}`,
		"/trending/tv/week":    `{"page":1,"results":[{"backdrop_path":"/b.jpg","id":2316,"name":"The Office","original_name":"The Office","overview":"A mockumentary.","poster_path":"/o.jpg","media_type":"tv","adult":false,"original_language":"en","genre_ids":[35],"popularity":300.1,"first_air_date":"2005-03-24","vote_average":8.6,"vote_count":4000,"origin_country":["US"]}],"total_pages":1,"total_results":1}`,
		"/trending/movie/week": `{"page":1,"results":[],"total_pages":1,"total_results":0}`,
		"/genre/movie/list":    `{"genres":[{"id":28,"name":"Action"},{"id":12,"name":"Adventure"}]}`,
		"/genre/tv/list":       `{"genres":[{"id":35,"name":"Comedy"}]}`,
	}
	srv := httptest.NewTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, ok := bodies[r.URL.Path]
		if !ok {
			http.NotFound(w, r)
			return
		}
		_, _ = io.WriteString(w, body)
	}))
	hc := srv.Client()
	c := &Client{apiKey: "secret", base: srv.URL, http: hc}

	movie, err := c.Details(t.Context(), MediaMovie, 603)
	if err != nil {
		t.Fatal(err)
	}
	if movie.Tagline != "Welcome to the Real World." || movie.RuntimeMinutes() != 136 || !slices.Equal(movie.GenreNames(), []string{"Action", "Science Fiction"}) || !slices.Equal(movie.TopCast(3), []string{"Keanu Reeves", "Laurence Fishburne"}) {
		t.Errorf("movie details = %+v", movie)
	}
	show, err := c.TV(t.Context(), 2316)
	if err != nil {
		t.Fatal(err)
	}
	if show.Name != "The Office" || len(show.Seasons) != 1 || show.Seasons[0] != (Season{SeasonNumber: 1, Name: "Season 1", EpisodeCount: 6, AirDate: "2005-03-24"}) {
		t.Errorf("tv details = %+v", show)
	}
	season, err := c.Season(t.Context(), 2316, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(season.Episodes) != 1 || season.Episodes[0] != (Episode{EpisodeNumber: 1, Name: "Pilot", Overview: "The premiere.", AirDate: "2005-03-24", StillPath: "/x.jpg"}) {
		t.Errorf("season = %+v", season)
	}
	page, err := c.Discover(t.Context(), DiscoverParams{MediaType: MediaMovie})
	if err != nil {
		t.Fatal(err)
	}
	matrix := SearchResult{ID: 603, MediaType: MediaMovie, Title: "The Matrix", ReleaseDate: "1999-03-31", Overview: "A hacker learns the truth.", VoteAverage: 8.2, PosterPath: "/p.jpg"}
	if page.TotalPages != 42 || len(page.Results) != 1 || page.Results[0] != matrix {
		t.Errorf("discover = %+v", page)
	}
	trending, err := c.Trending(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	office := SearchResult{ID: 2316, MediaType: MediaTV, Name: "The Office", FirstAirDate: "2005-03-24", Overview: "A mockumentary.", VoteAverage: 8.6, PosterPath: "/o.jpg"}
	if len(trending) != 1 || trending[0] != office {
		t.Errorf("trending = %+v", trending)
	}
	genres, err := c.Genres(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if len(genres.Movie) != 2 || genres.Movie[0] != (Genre{ID: 28, Name: "Action"}) || len(genres.TV) != 1 {
		t.Errorf("genres = %+v", genres)
	}
}
