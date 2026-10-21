package apiserver

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"github.com/stupside/castor/internal/settings"
	"github.com/urfave/cli/v3"
)

func TestAPIServiceSettingsLoadFromNestedSections(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(path, []byte("servers:\n  api:\n    url: http://localhost:8400\n    token: secret\n    network:\n      timeout: 3s\n    devices:\n      roku:\n        app_id: channel-123\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	load := func() *Config {
		t.Helper()
		var got *Config
		command := &cli.Command{
			Name: "castor", Flags: []cli.Flag{settings.Flag},
			Action: func(_ context.Context, cmd *cli.Command) (err error) {
				got, err = settings.Load(cmd, defaults())
				return err
			},
		}
		if err := command.Run(context.Background(), []string{"castor", "--config", path}); err != nil {
			t.Fatal(err)
		}
		return got
	}
	if got := load(); got.Servers.API.URL != "http://localhost:8400" || got.Servers.API.Token != "secret" || got.Servers.API.Network.Timeout != 3*time.Second || got.Servers.API.Devices.Roku.AppID != "channel-123" {
		t.Errorf("nested API settings = %+v", got.Servers.API)
	}
	t.Setenv("CASTOR_SERVERS__API__NETWORK__TIMEOUT", "4s")
	if got := load().Servers.API.Network.Timeout; got != 4*time.Second {
		t.Errorf("API timeout from environment = %s, want 4s", got)
	}
}

func TestSubtitleConfigSupportsSourceSelectionAndOptionalFallback(t *testing.T) {
	for _, tc := range []struct {
		name string
		doc  string
		want SubtitleConfig
	}{
		{"scalar language", "cast:\n  subtitles: en\n", SubtitleConfig{Languages: []string{"en"}}},
		{"language with fallback", "cast:\n  subtitles:\n    languages: [en]\n    transcribe_if_missing: true\n", SubtitleConfig{Languages: []string{"en"}, TranscribeIfMissing: true}},
		{"ordered languages", "cast:\n  subtitles:\n    languages: [fr, en]\n    transcribe_if_missing: true\n", SubtitleConfig{Languages: []string{"fr", "en"}, TranscribeIfMissing: true}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "config.yaml")
			if err := os.WriteFile(path, []byte(tc.doc), 0o600); err != nil {
				t.Fatal(err)
			}
			command := &cli.Command{
				Name: "castor", Flags: []cli.Flag{settings.Flag},
				Action: func(_ context.Context, cmd *cli.Command) error {
					got, err := settings.Load(cmd, defaults())
					if err != nil {
						return err
					}
					if !reflect.DeepEqual(got.Cast.Subtitles, tc.want) {
						t.Errorf("subtitles = %+v, want %+v", got.Cast.Subtitles, tc.want)
					}
					return nil
				},
			}
			if err := command.Run(context.Background(), []string{"castor", "--config", path}); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestAudioConfigAcceptsOneOrSeveralPreferredLanguages(t *testing.T) {
	for _, tc := range []struct {
		doc  string
		want []string
	}{
		{"cast:\n  audio: fr\n", []string{"fr"}},
		{"cast:\n  audio:\n    languages: [fr, en]\n", []string{"fr", "en"}},
	} {
		path := filepath.Join(t.TempDir(), "config.yaml")
		if err := os.WriteFile(path, []byte(tc.doc), 0o600); err != nil {
			t.Fatal(err)
		}
		command := &cli.Command{
			Name: "castor", Flags: []cli.Flag{settings.Flag},
			Action: func(_ context.Context, cmd *cli.Command) error {
				got, err := settings.Load(cmd, defaults())
				if err != nil {
					return err
				}
				if !reflect.DeepEqual(got.Cast.Audio.Languages, tc.want) {
					t.Errorf("audio languages = %v, want %v", got.Cast.Audio.Languages, tc.want)
				}
				return nil
			},
		}
		if err := command.Run(context.Background(), []string{"castor", "--config", path}); err != nil {
			t.Fatal(err)
		}
	}
}

func TestSubtitleConfigurationRejectsConflictingOrIncompleteSelection(t *testing.T) {
	for _, doc := range []string{
		"cast:\n  subtitles:\n    language: en\n    languages: [fr]\n",
		"cast:\n  subtitles:\n    transcribe_if_missing: true\n",
	} {
		path := filepath.Join(t.TempDir(), "config.yaml")
		if err := os.WriteFile(path, []byte(doc), 0o600); err != nil {
			t.Fatal(err)
		}
		command := &cli.Command{
			Name: "castor", Flags: []cli.Flag{settings.Flag},
			Action: func(_ context.Context, cmd *cli.Command) error {
				cfg, err := settings.Load(cmd, defaults())
				if err != nil {
					return err
				}
				_, err = New(Backend{}, cfg.Cast)
				return err
			},
		}
		if err := command.Run(context.Background(), []string{"castor", "--config", path}); err == nil {
			t.Errorf("accepted invalid subtitle configuration %q", doc)
		}
	}
}
