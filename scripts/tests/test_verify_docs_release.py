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
from unittest.mock import Mock, call, patch
from urllib.error import HTTPError, URLError

spec = importlib.util.spec_from_file_location("verify_docs_release", Path(__file__).parents[1] / "verify-docs-release.py")
docs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(docs)

COMMIT = "a" * 40
TAG_OBJECT = "b" * 40
PUBLISHED = {"tag_name": "v0.2.0", "draft": False, "prerelease": False, "published_at": "2026-11-14T12:00:00Z"}


class VerifyDocsReleaseTest(unittest.TestCase):
    def test_latest_stable_release_from_lightweight_or_annotated_tag(self):
        for annotated in (False, True):
            with self.subTest(annotated=annotated), patch.object(docs, "verify_portal") as portal:
                github = Mock()
                targets = [{"object": {"type": "commit", "sha": COMMIT}}]
                if annotated:
                    targets.insert(0, {"object": {"type": "tag", "sha": TAG_OBJECT}})
                github.request.side_effect = [PUBLISHED, PUBLISHED, *targets]
                self.assertEqual("0.2.0", docs.verify_docs_release(github, "v0.2.0", COMMIT))
                expected = [call("GET", "releases/tags/v0.2.0"), call("GET", "releases/latest"),
                            call("GET", "git/ref/tags/v0.2.0")]
                if annotated:
                    expected.append(call("GET", f"git/tags/{TAG_OBJECT}"))
                self.assertEqual(expected, github.request.call_args_list)
                portal.assert_called_once_with("0.2.0")

    def test_branches_prereleases_and_invalid_tags_are_rejected_without_requests(self):
        for tag in ("main", "refs/heads/main", "0.2.0", "v0.3.0-rc.1", "v0.3.0-SNAPSHOT",
                    "v0.2", "v01.2.0", "v0.2.0+build", "v0.2.0\n", ""):
            with self.subTest(tag=tag), patch.object(docs, "verify_portal") as portal:
                github = Mock()
                with self.assertRaisesRegex(ValueError, "stable release tag"):
                    docs.verify_docs_release(github, tag, COMMIT)
                github.request.assert_not_called()
                portal.assert_not_called()

    def test_requires_an_exact_commit(self):
        for commit in ("main", "v0.2.0", "abc1234", "", COMMIT + "\n"):
            with self.subTest(commit=commit):
                github = Mock()
                with self.assertRaisesRegex(ValueError, "full release commit"):
                    docs.verify_docs_release(github, "v0.2.0", commit)
                github.request.assert_not_called()

    def test_drafts_prereleases_and_unpublished_releases_are_rejected(self):
        for change in ({"draft": True}, {"prerelease": True}, {"published_at": None}, {"tag_name": "v0.1.0"}):
            with self.subTest(change=change), patch.object(docs, "verify_portal") as portal:
                github = Mock()
                github.request.return_value = {**PUBLISHED, **change}
                with self.assertRaisesRegex(ValueError, "published, stable GitHub Release"):
                    docs.verify_docs_release(github, "v0.2.0", COMMIT)
                github.request.assert_called_once_with("GET", "releases/tags/v0.2.0")
                portal.assert_not_called()

    def test_rerunning_an_older_release_cannot_replace_the_latest_docs(self):
        github = Mock()
        github.request.side_effect = [PUBLISHED, {**PUBLISHED, "tag_name": "v0.3.0"}]
        with patch.object(docs, "verify_portal") as portal, self.assertRaisesRegex(ValueError, "latest stable release"):
            docs.verify_docs_release(github, "v0.2.0", COMMIT)
        portal.assert_not_called()

    def test_moved_tag_cannot_publish_different_sources(self):
        github = Mock()
        github.request.side_effect = [PUBLISHED, PUBLISHED, {"object": {"type": "commit", "sha": "c" * 40}}]
        with patch.object(docs, "verify_portal") as portal, self.assertRaisesRegex(ValueError, "no longer points"):
            docs.verify_docs_release(github, "v0.2.0", COMMIT)
        portal.assert_not_called()

    def test_missing_release_or_failed_lookup_blocks_deployment(self):
        for responses in ([RuntimeError("HTTP 404")], [PUBLISHED, RuntimeError("HTTP 403")]):
            with self.subTest(responses=responses), patch.object(docs, "verify_portal") as portal:
                github = Mock()
                github.request.side_effect = responses
                with self.assertRaises(RuntimeError):
                    docs.verify_docs_release(github, "v0.2.0", COMMIT)
                portal.assert_not_called()

    def test_portal_publication_failure_blocks_deployment(self):
        github = Mock()
        github.request.side_effect = [PUBLISHED, PUBLISHED, {"object": {"type": "commit", "sha": COMMIT}}]
        with patch.object(docs, "verify_portal", side_effect=RuntimeError("Publication pending")):
            with self.assertRaisesRegex(RuntimeError, "Publication pending"):
                docs.verify_docs_release(github, "v0.2.0", COMMIT)


class VerifyPortalTest(unittest.TestCase):
    def test_both_markers_and_implementation_poms_and_jars_are_publicly_available(self):
        with patch.object(docs, "urlopen") as open_url:
            open_url.return_value.__enter__.return_value.status = 200
            docs.verify_portal("0.2.0")
            base = "https://plugins.gradle.org/m2/"
            self.assertEqual([
                base + "io/github/gregwhitaker/aws-emulator/io.github.gregwhitaker.aws-emulator.gradle.plugin/0.2.0/io.github.gregwhitaker.aws-emulator.gradle.plugin-0.2.0.pom",
                base + "io/github/gregwhitaker/gradle-aws-emulator/0.2.0/gradle-aws-emulator-0.2.0.pom",
                base + "io/github/gregwhitaker/gradle-aws-emulator/0.2.0/gradle-aws-emulator-0.2.0.jar",
                base + "io/github/gregwhitaker/aws-emulator/spring-boot/io.github.gregwhitaker.aws-emulator.spring-boot.gradle.plugin/0.2.0/io.github.gregwhitaker.aws-emulator.spring-boot.gradle.plugin-0.2.0.pom",
                base + "io/github/gregwhitaker/gradle-aws-emulator-spring-boot/0.2.0/gradle-aws-emulator-spring-boot-0.2.0.pom",
                base + "io/github/gregwhitaker/gradle-aws-emulator-spring-boot/0.2.0/gradle-aws-emulator-spring-boot-0.2.0.jar",
            ], [args.args[0].full_url for args in open_url.call_args_list])
            for args in open_url.call_args_list:
                self.assertEqual("HEAD", args.args[0].method)
                self.assertIsNone(args.args[0].get_header("Authorization"))
                self.assertEqual(30, args.kwargs["timeout"])

    def test_any_missing_artifact_blocks_deployment_including_partial_publication(self):
        for missing in range(6):
            with self.subTest(missing=missing), patch.object(docs, "urlopen") as open_url:
                response = open_url.return_value
                response.__enter__.return_value.status = 200
                open_url.side_effect = [response] * missing + [HTTPError("https://plugins.gradle.org/", 404, "Not found", {}, None)]
                with self.assertRaisesRegex(RuntimeError, "not publicly available"):
                    docs.verify_portal("0.2.0")

    def test_portal_network_failure_blocks_deployment(self):
        with patch.object(docs, "urlopen", side_effect=URLError("Unavailable")):
            with self.assertRaisesRegex(RuntimeError, "not publicly available"):
                docs.verify_portal("0.2.0")


if __name__ == "__main__":
    unittest.main()
