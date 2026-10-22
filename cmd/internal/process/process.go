// Package process is what every castor binary runs on: its signals, its logger, its global flags and its build identity.
package process

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"runtime/debug"
	"syscall"
	"time"

	"charm.land/log/v2"
	"github.com/urfave/cli/v3"

	"github.com/stupside/castor/internal/castid"
	"github.com/stupside/castor/internal/logging"
	"github.com/stupside/castor/internal/settings"
	"github.com/stupside/castor/internal/telemetry"
)

// version is stamped at link time; the commit and its time come from the build's VCS stamp.
var version = "dev"

const debugFlag = "debug"

// Flags are the root flags every castor binary reads its configuration and verbosity through.
var Flags = []cli.Flag{settings.Flag, &cli.BoolFlag{Name: debugFlag, Usage: "Enable debug logging"}}

// Run runs root until it returns or the process is interrupted, exiting non-zero on its error.
func Run(root *cli.Command, observed func(*cli.Command) bool) {
	slog.SetDefault(logger(log.InfoLevel))

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	// The first signal asks for a clean stop; restoring the default lets a second one end the process at once.
	context.AfterFunc(ctx, stop)

	if err := run(ctx, root, os.Args, observed); err != nil {
		os.Exit(1)
	}
}

// run owns telemetry for the entire command, including any embedded services.
// It flushes after reporting the command outcome and before Run may call os.Exit.
func run(ctx context.Context, root *cli.Command, args []string, observed func(*cli.Command) bool) error {
	shutdown := func(context.Context) error { return nil }
	defer func() {
		flushCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
		defer cancel()
		if err := shutdown(flushCtx); err != nil {
			slog.WarnContext(flushCtx, "telemetry shutdown failed", "error", err)
		}
	}()
	root.Version = version
	root.Flags = append(root.Flags, Flags...)
	before := root.Before
	root.Before = func(ctx context.Context, cmd *cli.Command) (context.Context, error) {
		if cmd.Bool(debugFlag) {
			slog.SetDefault(logger(log.DebugLevel))
		}
		if before != nil {
			return before(ctx, cmd)
		}
		return ctx, nil
	}
	// Instrument actual actions, after parsing/validation. The composition root
	// identifies local-only actions that intentionally need no runtime config.
	if err := root.Walk(func(command *cli.Command) error {
		action := command.Action
		if action == nil {
			return nil
		}
		command.Action = func(ctx context.Context, cmd *cli.Command) error {
			if observed == nil || observed(cmd) {
				cfg, err := settings.Load(cmd, struct {
					Telemetry telemetry.Config `yaml:"telemetry" settings:"strict"`
				}{})
				if err != nil {
					return err
				}
				stop, err := telemetry.Setup(ctx, cfg.Telemetry, cmp.Or(os.Getenv("OTEL_SERVICE_NAME"), root.Name), version)
				if err != nil {
					return err
				}
				shutdown = stop
			}
			return action(ctx, cmd)
		}
		return nil
	}); err != nil {
		return err
	}
	// Build information never depends on working runtime configuration.
	root.Commands = append(root.Commands, info())
	err := root.Run(ctx, args)
	if err != nil {
		// Only a single cancellation failure is a clean signal shutdown. A joined
		// error can carry a failed flush/cleanup beside cancellation and must survive.
		leaf := err
		for {
			wrapped, ok := leaf.(interface{ Unwrap() error })
			if !ok {
				break
			}
			leaf = wrapped.Unwrap()
		}
		_, multiple := leaf.(interface{ Unwrap() []error })
		if cause := context.Cause(ctx); errors.Is(cause, context.Canceled) && !multiple && errors.Is(leaf, context.Canceled) {
			slog.InfoContext(ctx, "shutting down", "cause", cause)
			return nil
		}
		slog.ErrorContext(ctx, "application error", "error", err)
	}
	return err
}

func info() *cli.Command {
	return &cli.Command{
		Name:  "info",
		Usage: "Print build information",
		Action: func(context.Context, *cli.Command) error {
			commit, built := "none", "unknown"
			if info, ok := debug.ReadBuildInfo(); ok {
				for _, s := range info.Settings {
					switch s.Key {
					case "vcs.revision":
						commit = s.Value
					case "vcs.time":
						built = s.Value
					}
				}
			}
			fmt.Printf("version    %s\n", version)
			fmt.Printf("commit     %s\n", commit)
			fmt.Printf("build time %s\n", built)
			return nil
		},
	}
}

func logger(level log.Level) *slog.Logger {
	screen := log.NewWithOptions(os.Stderr, log.Options{
		ReportTimestamp: true,
		TimeFormat:      "15:04:05.000",
		Level:           level,
	})
	return slog.New(logging.Redact(slog.NewMultiHandler(castid.Tagged(screen, castid.LogKey), telemetry.Handler())))
}
