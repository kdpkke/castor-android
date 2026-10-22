#!/usr/bin/env node
// Runs the castor binary shipped by the platform package npm installed for this machine.
import { spawn } from "node:child_process";
import { constants } from "node:os";

const pkg = `@castor-dev/cli-${process.platform}-${process.arch}`;
const exe = process.platform === "win32" ? "castor.exe" : "castor";

let bin: string;
try {
  bin = require.resolve(`${pkg}/${exe}`);
} catch {
  console.error(`castor has no build for ${process.platform}-${process.arch} (missing ${pkg}).`);
  process.exit(1);
}

const child = spawn(bin, process.argv.slice(2), { stdio: "inherit" });

// The terminal already sends Ctrl-C to the child; forwarding it again would force-quit a graceful shutdown.
process.on("SIGINT", () => {});
for (const signal of ["SIGTERM", "SIGHUP"] as const) process.on(signal, () => child.kill(signal));

child.on("error", (err) => {
  console.error(`castor failed to start: ${err.message}`);
  process.exit(1);
});
child.on("exit", (code, signal) => process.exit(code ?? 128 + (signal ? constants.signals[signal] : 0)));
