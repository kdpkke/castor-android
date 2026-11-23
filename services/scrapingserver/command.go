package scrapingserver

import (
	"context"
	"log/slog"
	"net"
	"time"

	"github.com/stupside/castor/internal/settings"
	"github.com/stupside/castor/internal/transport"
	"github.com/stupside/castor/services/scrapingserver/internal/extract"
	"github.com/urfave/cli/v3"
)

type ServerConfig struct {
	transport.Endpoint `yaml:",inline"`
	Listen             string `yaml:"listen" validate:"required,hostname_port|startswith=:"`
}

type ServiceConfig struct {
	Servers struct {
		Scraping ScrapingConfig `yaml:"scraping" settings:"strict" validate:"required"`
	} `yaml:"servers" validate:"required"`
}

type ScrapingConfig struct {
	ServerConfig   `yaml:",inline"`
	extract.Config `yaml:",inline"`
}

func defaults() ServiceConfig {
	c := ServiceConfig{}
	c.Servers.Scraping = ScrapingConfig{
		Listen:  ":8412",
		Browser: extract.BrowserConfig{Timeout: 30 * time.Second, Headless: true}, Capture: extract.CaptureConfig{MaxConcurrency: 4},
	}
	return c
}

func Command() *cli.Command {
	return &cli.Command{Name: "scraping-server", Usage: "Resolve web pages into playable stream candidates", Action: func(ctx context.Context, cmd *cli.Command) error {
		cfg, err := settings.Load(cmd, defaults())
		if err != nil {
			return err
		}
		listener, err := transport.Listen(ctx, cfg.Servers.Scraping.Listen, cfg.Servers.Scraping.Token, "servers.scraping.token")
		if err != nil {
			return err
		}
		srv := New(extract.New(cfg.Servers.Scraping.Config))
		slog.InfoContext(ctx, "scraping serving", "address", listener.Addr().String())
		return transport.Serve(ctx, listener, srv.Authorized(cfg.Servers.Scraping.Token), srv.Shutdown)
	}}
}

// Embedded starts the resolver on loopback when the CLI has no remote scrapingserver.
func Embedded(ctx context.Context, cmd *cli.Command) (transport.Endpoint, func(), error) {
	cfg, err := settings.Load(cmd, defaults())
	if err != nil {
		return transport.Endpoint{}, nil, err
	}
	srv := New(extract.New(cfg.Servers.Scraping.Config))
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return transport.Endpoint{}, nil, err
	}
	stop := transport.Background(ctx, listener, srv.Authorized(cfg.Servers.Scraping.Token), srv.Shutdown)
	return transport.Endpoint{URL: "http://" + listener.Addr().String(), Token: cfg.Servers.Scraping.Token}, stop, nil
}
