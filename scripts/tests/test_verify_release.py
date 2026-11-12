#
# Copyright 2026 Greg Whitaker
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("verify_release", Path(__file__).parents[1] / "verify-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class VerifyReleaseTest(unittest.TestCase):
    def verify(self, version="0.2.0", heading="0.2.0 — 2026-11-12", ref_type="tag", ref_name="v0.2.0"):
        return release.verify_release(f"pluginVersion={version}\n", f"# Changelog\n\n## {heading}\n", ref_type, ref_name)

    def test_matching_release(self):
        self.assertEqual("0.2.0", self.verify())

    def test_prerelease(self):
        self.assertEqual("0.2.0-rc.1", self.verify(version="0.2.0-rc.1", heading="0.2.0-rc.1 — 2026-11-12", ref_name="v0.2.0-rc.1"))

    def test_crlf_files(self):
        self.assertEqual("0.2.0", release.verify_release("pluginVersion=0.2.0\r\n", "## 0.2.0 — 2026-11-12\r\n", "tag", "v0.2.0"))

    def test_invalid_versions(self):
        for version in ("", "0.2.0-SNAPSHOT", "0.2.0-rc.01", "0.2.0-snapshot", "0.2", "v0.2.0", "0.2.0 ", "01.2.0", "0.2.0+build"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                self.verify(version=version)

    def test_missing_or_duplicate_property(self):
        for properties in ("", "pluginVersion=0.2.0\npluginVersion=0.2.0\n"):
            with self.subTest(properties=properties), self.assertRaises(ValueError):
                release.verify_release(properties, "## 0.2.0 — 2026-11-12", "tag", "v0.2.0")

    def test_wrong_ref(self):
        for ref_type, ref_name in (("branch", "main"), ("branch", "v0.2.0"), ("tag", "v0.1.0"), ("", "")):
            with self.subTest(ref_type=ref_type, ref_name=ref_name), self.assertRaises(ValueError):
                self.verify(ref_type=ref_type, ref_name=ref_name)

    def test_unreleased_mismatched_or_invalid_date(self):
        for heading in ("0.2.0 — Unreleased", "0.1.0 — 2026-11-12", "0.2.0 — 2026-02-30", "0.2.0 — 2026-13-01", "0.2.0 — 26-11-12"):
            with self.subTest(heading=heading), self.assertRaises(ValueError):
                self.verify(heading=heading)

    def test_only_top_section_can_match(self):
        with self.assertRaises(ValueError):
            release.verify_release("pluginVersion=0.2.0", "## 0.3.0 — Unreleased\n## 0.2.0 — 2026-11-12", "tag", "v0.2.0")

    def test_missing_changelog_section(self):
        with self.assertRaises(ValueError):
            release.verify_release("pluginVersion=0.2.0", "# Changelog", "tag", "v0.2.0")


if __name__ == "__main__":
    unittest.main()
