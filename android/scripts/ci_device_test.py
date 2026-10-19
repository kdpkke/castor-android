import os
from pathlib import Path
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.name == "posix", "CI emulator setup runs on Linux")
class AvdTargetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="castor-avd-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.script = Path(__file__).with_name("ci-device.sh").resolve()
        self.env = dict(os.environ, RUNNER_TEMP=str(self.root / "runner"),
                        ANDROID_HOME=str(self.root / "sdk"),
                        ANDROID_CMDLINE_TOOLS_VERSION="23.0",
                        MOCK_TARGET="android-37.2", MOCK_CONFIG_TARGET="android-37.2")
        for version in ("latest", "23.0"):
            self.executable(f"sdk/cmdline-tools/{version}/bin/avdmanager", """#!/usr/bin/env python3
import os
from pathlib import Path
import sys
sys.stdin.read()
root = Path(os.environ['RUNNER_TEMP'])
(root / 'manager-version').write_text(Path(__file__).parent.parent.name)
avd = Path(sys.argv[sys.argv.index('--path') + 1])
avd.mkdir(parents=True)
(avd / 'config.ini').write_text('target=' + os.environ['MOCK_CONFIG_TARGET'] + '\\n')
(avd.parent / 'castor-ci.ini').write_text('target=' + os.environ['MOCK_TARGET'] + '\\n')
""")
        # Stop at the first emulator command: these tests exercise AVD setup only.
        self.executable("sdk/emulator/emulator", """#!/usr/bin/env python3
import os
from pathlib import Path
(Path(os.environ['RUNNER_TEMP']) / 'emulator-called').touch()
raise SystemExit(1)
""")

    def executable(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        path.chmod(0o755)

    def run_setup(self):
        return subprocess.run(["bash", str(self.script)], cwd=self.root, env=self.env,
                              text=True, capture_output=True, timeout=10)

    def test_versioned_tools_and_valid_target_reach_emulator(self):
        self.run_setup()
        runner = self.root / "runner"
        self.assertEqual((runner / "manager-version").read_text(), "23.0")
        self.assertTrue((runner / "emulator-called").exists())

    def test_invalid_target_is_rejected_before_emulator(self):
        for field in ("MOCK_TARGET", "MOCK_CONFIG_TARGET"):
            for target in ("android-0", "android-37.1", ""):
                with self.subTest(field=field, target=target):
                    # Each setup owns a fresh AVD, just as a new CI job does.
                    self.env["RUNNER_TEMP"] = str(self.root / f"{field}-{target or 'missing'}")
                    self.env[field] = target
                    result = self.run_setup()
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("Invalid AVD target", result.stderr)
                    self.assertFalse((Path(self.env["RUNNER_TEMP"]) / "emulator-called").exists())
            self.env[field] = "android-37.2"


if __name__ == "__main__":
    unittest.main()
