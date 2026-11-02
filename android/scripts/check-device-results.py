#!/usr/bin/env python3
"""Reject successful Gradle builds that did not actually execute the device suite."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


tests = 0
for path in Path("android/app/build/outputs/androidTest-results/connected/debug").rglob("*.xml"):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    for suite in suites:
        if any(int(suite.get(key, "0")) != 0 for key in ("failures", "errors", "skipped")):
            for case in suite.findall("testcase"):
                for result in case:
                    if result.tag in ("failure", "error", "skipped"):
                        print(f"{case.get('classname')}#{case.get('name')}: "
                              f"{result.text or result.get('message', result.tag)}")
            sys.exit(f"Device tests failed or were skipped: {path}")
        tests += int(suite.get("tests", "0"))
if tests < 28:
    sys.exit(f"Only {tests} device tests ran; expected at least 28")
print(f"Verified {tests} device tests, no failures/errors/skips")
