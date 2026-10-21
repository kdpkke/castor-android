package settings

// Endpoint is the URL and bearer token used to reach a Castor service.
// A service's own command uses Token; clients also use URL.
type Endpoint struct {
	URL   string `yaml:"url" validate:"omitempty,http_url"`
	Token string `yaml:"token"`
}
