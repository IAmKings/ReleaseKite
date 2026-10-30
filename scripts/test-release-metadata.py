#!/usr/bin/env python3
"""Run the release workflow's metadata generator against policy edge cases."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


class ReleaseMetadataTest(unittest.TestCase):
    def generate(self, policy):
        workflow = (Path(__file__).resolve().parents[1] / "samples/github-workflows/release.yml").read_text()
        start = workflow.index("          import datetime, json, os, re")
        code = textwrap.dedent(workflow[start:workflow.index("\n          PY", start)])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "policy.json"
            path.write_text(json.dumps(policy))
            env = dict(os.environ, UPDATE_POLICY_FILE=str(path), TAG_NAME="v1.2.3",
                       CHANNEL="stable", HTML_URL="https://github.com/example/app",
                       APK_NAME="app.apk", SIZE="1024", SHA256="a" * 64)
            return subprocess.run([os.sys.executable, "-c", code], env=env,
                                  capture_output=True, text=True)

    def test_optional_minimum_version(self):
        for policy in ({}, {"minSupportedVersion": None}, {"minSupportedVersion": ""},
                       {"minSupportedVersion": "1.0.0"}):
            with self.subTest(policy=policy):
                result = self.generate(policy)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(policy.get("minSupportedVersion") or None,
                                 json.loads(result.stdout)["minSupportedVersion"])

    def test_invalid_version_is_rejected(self):
        for value in ("not-a-version", False, {}, []):
            with self.subTest(value=value):
                result = self.generate({"minSupportedVersion": value})
                self.assertNotEqual(0, result.returncode)
                self.assertIn("invalid minSupportedVersion", result.stderr)


if __name__ == "__main__":
    unittest.main()
