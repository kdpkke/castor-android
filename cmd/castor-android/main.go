// Command castor-android runs the existing API and media engine inside the Android foreground service.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"time"

	"github.com/urfave/cli/v3"

	"github.com/stupside/castor/cmd/internal/process"
	castorv1 "github.com/stupside/castor/gen/castor/v1"
	"github.com/stupside/castor/gen/castor/v1/castorv1connect"
	"github.com/stupside/castor/internal/transport"
	"github.com/stupside/castor/services/apiserver"
	"github.com/stupside/castor/services/mediaserver"
)

func main() {
	process.Run(&cli.Command{
		Name: "castor-android",
		Action: func(ctx context.Context, cmd *cli.Command) error {
			apiToken, mediaToken, scrapingToken := os.Getenv("CASTOR_SERVERS__API__TOKEN"), os.Getenv("CASTOR_SERVERS__MEDIA__TOKEN"), os.Getenv("CASTOR_SERVERS__SCRAPING__TOKEN")
			if apiToken == "" || mediaToken == "" || scrapingToken == "" || apiToken == mediaToken || apiToken == scrapingToken || mediaToken == scrapingToken {
				return errors.New("the Android service must supply independent API, media, and scraping tokens")
			}
			browser := func(context.Context, *cli.Command) (transport.Endpoint, func(), error) {
				return transport.Endpoint{}, nil, errors.New("servers.scraping.url must name the Android browser service")
			}
			native := castorv1connect.NewDeviceServiceClient(transport.Bearer(os.Getenv("CASTOR_SERVERS__SCRAPING__TOKEN")), os.Getenv("CASTOR_SERVERS__SCRAPING__URL"), transport.Options())
			discovery := func(ctx context.Context) ([]*castorv1.Device, error) {
				response, err := native.ListDevices(ctx, &castorv1.ListDevicesRequest{})
				if err != nil {
					return nil, err
				}
				return response.GetDevices(), nil
			}
			at, stop, err := apiserver.EmbeddedWithDiscovery(mediaserver.Embedded, browser, discovery)(ctx, cmd)
			if err != nil {
				return err
			}
			defer stop()
			output := json.NewEncoder(os.Stdout)
			if err := output.Encode(struct {
				URL string `json:"url"`
			}{at.URL}); err != nil {
				return err
			}
			casts := castorv1connect.NewCastServiceClient(at.Client(), at.URL, transport.Options())
			ticker := time.NewTicker(time.Second)
			defer ticker.Stop()
			active := false
			// A busy phone can miss a check; only a persistently silent engine ends the host.
			const maxMissedChecks = 5
			missed := 0
			for {
				select {
				case <-ctx.Done():
					return nil
				case <-ticker.C:
					check, cancel := context.WithTimeout(ctx, 5*time.Second)
					listed, err := casts.ListCasts(check, &castorv1.ListCastsRequest{})
					cancel()
					if ctx.Err() != nil {
						return nil
					}
					if err != nil {
						if missed++; missed >= maxMissedChecks {
							return err
						}
						continue
					}
					missed = 0
					now := len(listed.GetCasts()) > 0
					if now != active {
						if err := output.Encode(struct {
							Active bool `json:"active"`
						}{now}); err != nil {
							return err
						}
						active = now
					}
				}
			}
		},
	}, nil)
}
