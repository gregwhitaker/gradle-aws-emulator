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
import io
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, call, patch
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location("publish_github_release", Path(__file__).parents[1] / "publish-github-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

COMMIT = "a" * 40
TAG_OBJECT = "b" * 40
URL = "https://github.com/owner/repository/releases/tag/v0.2.0"


class ReleaseNotesTest(unittest.TestCase):
    def test_only_current_section_including_subheadings(self):
        for newline in ("\n", "\r\n"):
            with self.subTest(newline=newline):
                changelog = newline.join((
                    "# Changelog", "", "## 0.2.0 — 2026-11-14", "",
                    "### Added", "", "- New feature.", "", "### Fixed", "", "- Bug fix.", "",
                    "## 0.1.0 — 2026-11-01", "", "- Previous release.",
                ))
                version, notes = release.release_notes("pluginVersion=0.2.0", changelog, "tag", "v0.2.0")
                self.assertEqual("0.2.0", version)
                self.assertEqual(newline.join(("### Added", "", "- New feature.", "", "### Fixed", "", "- Bug fix.")), notes)

    def test_last_section(self):
        self.assertEqual(("0.2.0", "Release notes."), release.release_notes(
            "pluginVersion=0.2.0", "## 0.2.0 — 2026-11-14\n\nRelease notes.\n", "tag", "v0.2.0"))

    def test_invalid_metadata_or_empty_notes(self):
        for heading, ref_type, ref_name, notes in (
            ("0.2.0 — Unreleased", "tag", "v0.2.0", "Notes"),
            ("0.2.0 — 2026-11-14", "branch", "main", "Notes"),
            ("0.2.0 — 2026-11-14", "tag", "v0.3.0", "Notes"),
            ("0.2.0 — 2026-11-14", "tag", "v0.2.0", " \n\n"),
        ):
            with self.subTest(heading=heading, ref_name=ref_name, notes=notes), self.assertRaises(ValueError):
                release.release_notes("pluginVersion=0.2.0", f"## {heading}\n{notes}\n## 0.1.0 — 2026-11-01\nOld notes", ref_type, ref_name)


class PublishReleaseTest(unittest.TestCase):
    def test_creates_stable_and_prerelease_from_existing_tag(self):
        for version in ("0.2.0", "0.2.0-rc.1"):
            with self.subTest(version=version):
                github = Mock()
                github.request.side_effect = [
                    {"object": {"type": "commit", "sha": COMMIT}}, None, [], {"html_url": URL},
                ]
                self.assertEqual(URL, release.publish_release(github, version, "Notes", COMMIT))
                self.assertEqual([
                    call("GET", f"git/ref/tags/v{version}"),
                    call("GET", f"releases/tags/v{version}", missing_ok=True),
                    call("GET", "releases?per_page=100&page=1"),
                    call("POST", "releases", {
                        "tag_name": f"v{version}", "target_commitish": COMMIT, "name": f"v{version}",
                        "body": "Notes", "draft": False, "prerelease": "-" in version,
                    }),
                ], github.request.call_args_list)

    def test_resolves_annotated_tag_and_preserves_existing_release_on_retry(self):
        github = Mock()
        github.request.side_effect = [
            {"object": {"type": "tag", "sha": TAG_OBJECT}},
            {"object": {"type": "commit", "sha": COMMIT}},
            {"draft": False, "html_url": URL, "body": "Maintainer's edited notes"},
        ]
        self.assertEqual(URL, release.publish_release(github, "0.2.0", "New notes", COMMIT))
        self.assertEqual([
            call("GET", "git/ref/tags/v0.2.0"), call("GET", f"git/tags/{TAG_OBJECT}"),
            call("GET", "releases/tags/v0.2.0", missing_ok=True),
        ], github.request.call_args_list)

    def test_publishes_existing_draft_without_overwriting_notes_or_assets(self):
        github = Mock()
        github.request.side_effect = [
            {"object": {"type": "commit", "sha": COMMIT}},
            None,
            [{"tag_name": "v0.2.0-rc.1", "draft": True, "id": 123, "body": "Reviewed notes", "assets": [{"id": 456}]}],
            {"html_url": URL},
        ]
        self.assertEqual(URL, release.publish_release(github, "0.2.0-rc.1", "Generated notes", COMMIT))
        github.request.assert_called_with("PATCH", "releases/123", {"draft": False, "prerelease": True})

    def test_finds_draft_beyond_first_page(self):
        github = Mock()
        draft = {"tag_name": "v0.2.0", "draft": True, "id": 123}
        github.request.side_effect = [None, [{"tag_name": f"v0.1.{index}"} for index in range(100)], [draft]]
        self.assertEqual(draft, release.find_release(github, "v0.2.0"))
        github.request.assert_called_with("GET", "releases?per_page=100&page=2")

    def test_moved_or_non_commit_tag_never_creates_release(self):
        for target in ({"type": "commit", "sha": "c" * 40}, {"type": "tree", "sha": COMMIT}):
            with self.subTest(target=target):
                github = Mock()
                github.request.return_value = {"object": target}
                with self.assertRaises(ValueError):
                    release.publish_release(github, "0.2.0", "Notes", COMMIT)
                github.request.assert_called_once_with("GET", "git/ref/tags/v0.2.0")

    def test_missing_tag_or_failed_release_lookup_never_creates_release(self):
        for responses in (
            [RuntimeError("HTTP 404")],
            [{"object": {"type": "commit", "sha": COMMIT}}, RuntimeError("HTTP 403")],
            [{"object": {"type": "commit", "sha": COMMIT}}, None, RuntimeError("HTTP 403")],
        ):
            with self.subTest(responses=responses):
                github = Mock()
                github.request.side_effect = responses
                with self.assertRaises(RuntimeError):
                    release.publish_release(github, "0.2.0", "Notes", COMMIT)
                self.assertTrue(all(args[0][0] == "GET" for args in github.request.call_args_list))

    def test_create_failure_is_reported(self):
        github = Mock()
        github.request.side_effect = [
            {"object": {"type": "commit", "sha": COMMIT}}, None, [], RuntimeError("HTTP 500"),
        ]
        with self.assertRaisesRegex(RuntimeError, "HTTP 500"):
            release.publish_release(github, "0.2.0", "Notes", COMMIT)


class GitHubRequestTest(unittest.TestCase):
    def test_authenticated_json_request(self):
        with patch.object(release, "urlopen") as open_url:
            open_url.return_value.__enter__.return_value = io.BytesIO(b'{"html_url": "release-url"}')
            self.assertEqual({"html_url": "release-url"}, release.GitHub("owner/repository", "test-token").request(
                "POST", "releases", {"body": "Notes"}))
            request = open_url.call_args.args[0]
            self.assertEqual("https://api.github.com/repos/owner/repository/releases", request.full_url)
            self.assertEqual("POST", request.method)
            self.assertEqual("Bearer test-token", request.get_header("Authorization"))
            self.assertEqual({"body": "Notes"}, json.loads(request.data))
            self.assertEqual(30, open_url.call_args.kwargs["timeout"])

    def test_only_optional_get_404_means_missing(self):
        for method, status, missing_ok in (("GET", 404, True), ("GET", 404, False), ("GET", 401, True), ("GET", 403, True), ("GET", 500, True), ("POST", 404, True)):
            with self.subTest(method=method, status=status, missing_ok=missing_ok):
                error = HTTPError("https://api.github.com/", status, "Failure", {}, None)
                with patch.object(release, "urlopen", side_effect=error):
                    github = release.GitHub("owner/repository", "test-token")
                    if method == "GET" and status == 404 and missing_ok:
                        self.assertIsNone(github.request(method, "releases/tags/v0.2.0", missing_ok=missing_ok))
                    else:
                        with self.assertRaisesRegex(RuntimeError, f"HTTP {status}"):
                            github.request(method, "releases/tags/v0.2.0", missing_ok=missing_ok)

    def test_requires_repository_and_credentials(self):
        for repository, token in (("", "token"), ("https://github.com/owner/repository", "token"), ("owner/repository", "")):
            with self.subTest(repository=repository, token=token), self.assertRaises(ValueError):
                release.GitHub(repository, token)


if __name__ == "__main__":
    unittest.main()
