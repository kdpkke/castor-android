package main

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stupside/castor/internal/settings"
	"github.com/urfave/cli/v3"
)

func TestCLIConfigChecksItsOwnSourceTemplates(t *testing.T) {
	for _, tc := range []struct {
		name, document string
		wantError      bool
	}{
		{"known template", "sources:\n  - proxies: [https://example.com]\n    templates:\n      movie: /movie/{itemID}\n      episode: /episode/{itemID}\n", false},
		{"unknown template", "sources:\n  - proxies: [https://example.com]\n    templates:\n      movvie: /movie/{itemID}\n", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "config.yaml")
			if err := os.WriteFile(path, []byte("device:\n  name: Living Room\n  type: dlna\n"+tc.document), 0o600); err != nil {
				t.Fatal(err)
			}
			cmd := &cli.Command{Name: "castor", Flags: []cli.Flag{settings.Flag}, Action: func(_ context.Context, cmd *cli.Command) error {
				_, err := settings.Load(cmd, Config{})
				return err
			}}
			err := cmd.Run(t.Context(), []string{"castor", "--config", path})
			if tc.wantError && (err == nil || !strings.Contains(err.Error(), "movvie")) {
				t.Fatalf("config error = %v, want unknown source template", err)
			}
			if !tc.wantError && err != nil {
				t.Fatal(err)
			}
		})
	}
}
