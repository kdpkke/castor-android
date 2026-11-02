from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class DeviceResultsTest(unittest.TestCase):
    def check(self, report):
        with tempfile.TemporaryDirectory(prefix="castor-results-test-") as temp:
            root = Path(temp)
            reports = root / "android/app/build/outputs/androidTest-results/connected/debug"
            reports.mkdir(parents=True)
            (reports / "TEST-device.xml").write_text(report, encoding="utf-8")
            script = Path(__file__).with_name("check-device-results.py").resolve()
            return subprocess.run([sys.executable, str(script)], cwd=root,
                                  text=True, capture_output=True)

    def test_failure_names_the_test_and_its_actual_error(self):
        result = self.check('''<testsuite tests="46" failures="1" errors="0" skipped="0">
          <testcase classname="SettingsTest" name="savesLanguage">
            <failure>Expected saved subtitle language IT</failure>
          </testcase></testsuite>''')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SettingsTest#savesLanguage", result.stdout + result.stderr)
        self.assertIn("Expected saved subtitle language IT", result.stdout + result.stderr)

    def test_complete_success_reports_the_actual_count(self):
        result = self.check('<testsuite tests="46" failures="0" errors="0" skipped="0"/>')
        self.assertEqual(result.returncode, 0)
        self.assertIn("Verified 46 device tests", result.stdout)

    def test_incomplete_suite_cannot_pass(self):
        result = self.check('<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Only 1 device tests ran", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
