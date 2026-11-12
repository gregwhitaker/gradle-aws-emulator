"""Exercise the smoke driver without downloading Gradle or publishing artifacts."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).parents[1] / "smoke-test-publication.sh"


class SmokePublicationTest(unittest.TestCase):
    def run_driver(self, mode="reuse", properties="pluginVersion=0.2.0\n"):
        with tempfile.TemporaryDirectory(prefix="publication smoke ") as directory:
            root = Path(directory)
            (root / "publication repository").mkdir()
            (root / "gradle.properties").write_text(properties)
            fake = root / "fake gradle"
            fake.write_text('''#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$CALLS"
if [[ "$MODE" == fail ]]; then exit 13; fi
if [[ "$MODE" == fail-after-reuse && "$(wc -l < "$CALLS")" -eq 2 ]]; then
  echo 'Configuration cache entry reused.'
  exit 13
fi
if [[ "$MODE" == reuse || "$MODE" == fail-after-reuse ]]; then echo 'Configuration cache entry reused.'; fi
''')
            fake.chmod(0o755)
            calls = root / "calls"
            env = dict(os.environ, GRADLE=str(fake), MODE=mode, CALLS=str(calls))
            result = subprocess.run(["bash", str(SCRIPT), "publication repository"], cwd=root, env=env, capture_output=True, text=True)
            return result, calls.read_text().splitlines() if calls.exists() else []

    def test_runs_both_consumers_twice(self):
        result, calls = self.run_driver()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(4, len(calls))
        self.assertIn("smoke-tests/core", calls[0])
        self.assertIn("smoke-tests/spring-boot", calls[2])
        self.assertEqual(calls[0], calls[1])
        self.assertEqual(calls[2], calls[3])
        for call in calls:
            self.assertIn("--configuration-cache-problems=fail", call)
            self.assertIn("--console=plain", call)
            self.assertIn("-PpluginVersion=0.2.0", call)

    def test_missing_reuse_fails(self):
        result, calls = self.run_driver("no-reuse")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(2, len(calls))
        self.assertIn("was not reused", result.stderr)

    def test_gradle_failure_stops_driver(self):
        result, calls = self.run_driver("fail")
        self.assertEqual(13, result.returncode)
        self.assertEqual(1, len(calls))

    def test_failure_is_not_hidden_by_tee_or_reuse_message(self):
        result, calls = self.run_driver("fail-after-reuse")
        self.assertEqual(13, result.returncode)
        self.assertEqual(2, len(calls))

    def test_missing_or_duplicate_version_fails_before_gradle(self):
        for properties in ("", "pluginVersion=\n", "pluginVersion=0.2.0\npluginVersion=0.2.1\n"):
            with self.subTest(properties=properties):
                result, calls = self.run_driver(properties=properties)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], calls)

    def test_missing_repository_fails(self):
        result = subprocess.run(["bash", str(SCRIPT), str(SCRIPT / "missing")], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("does not exist", result.stderr)


if __name__ == "__main__":
    unittest.main()
