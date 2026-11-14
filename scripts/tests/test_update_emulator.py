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
import tempfile
import unittest
from unittest.mock import MagicMock, patch


spec = importlib.util.spec_from_file_location("update_emulator", Path(__file__).parents[1] / "update-emulator.py")
updates = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updates)


def index(*versions):
    return {"versions": [{"productId": "aws-emulator", "releaseStream": "LTS", "version": version} for version in versions]}


def pull_request(sha="candidate-commit"):
    return {"html_url": "https://github.com/example/plugin/pull/12", "head": {
        "ref": "codex/update-aws-emulator-1.0.367", "sha": sha, "repo": {"full_name": "example/plugin"},
    }}


class ReleaseDiscoveryTest(unittest.TestCase):
    def test_orders_versions_numerically_and_strips_package_revision(self):
        self.assertEqual("1.0.367", updates.latest_version(index("1.0.9.1", "1.0.367.1", "1.0.99.1")))
        self.assertEqual("2.0.0", updates.latest_version(index("1.9.999.1", "2.0.0")))

    def test_ignores_other_products_streams_and_prereleases(self):
        data = index("1.0.367.1", "2.0.0-rc.1", "1.0.999; command", "01.0.999.1")
        data["versions"].extend([
            {"productId": "other", "releaseStream": "LTS", "version": "8.0.0.1"},
            {"productId": "aws-emulator", "releaseStream": "preview", "version": "9.0.0.1"},
        ])
        self.assertEqual("1.0.367", updates.latest_version(data))

    def test_refuses_unknown_schema_empty_feed_and_unsupported_latest_revision(self):
        for data in ({}, [], index(), index("1.0.366.1", "1.0.367.2")):
            with self.subTest(data=data), self.assertRaises(ValueError):
                updates.latest_version(data)

    def test_manifest_requires_one_exact_release_version(self):
        self.assertEqual("1.0.322", updates.read_version("# comment\r\nemulatorVersion=1.0.322\r\n"))
        for text in ("", "emulatorVersion=latest\n", "emulatorVersion=1.0.322 \n",
                     "emulatorVersion=1.0.322\nemulatorVersion=1.0.367\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                updates.read_version(text)

    def test_candidate_requires_stable_release_and_both_architectures(self):
        response = MagicMock()
        response.__enter__.return_value.status = 200
        release = {"tag_name": "v1.0.367", "draft": False, "prerelease": False}
        with patch.object(updates, "fetch_json", side_effect=[index("1.0.367.1"), release]), \
                patch.object(updates, "request", return_value=response) as request:
            self.assertEqual("1.0.367", updates.candidate("1.0.322"))
            urls = [call.args[0] for call in request.call_args_list]
            self.assertEqual([f"{updates.PACKAGES}/aws-emulator-1.0.367-1-{arch}.rpm"
                              for arch in ("x86_64", "aarch64")], urls)
            self.assertTrue(all(call.kwargs["method"] == "HEAD" for call in request.call_args_list))

    def test_no_downgrade_or_redundant_package_requests(self):
        for current in ("1.0.367", "1.0.368"):
            with self.subTest(current=current), patch.object(updates, "fetch_json", return_value=index("1.0.367.1")), \
                    patch.object(updates, "request") as request:
                self.assertIsNone(updates.candidate(current))
                request.assert_not_called()

    def test_unreleased_or_missing_package_fails_before_update(self):
        for release in ({"tag_name": "v1.0.367", "draft": True, "prerelease": False},
                        {"tag_name": "v1.0.367", "draft": False, "prerelease": True},
                        {"tag_name": "v1.0.368", "draft": False, "prerelease": False}):
            with self.subTest(release=release), \
                    patch.object(updates, "fetch_json", side_effect=[index("1.0.367.1"), release]), \
                    patch.object(updates, "request") as request, self.assertRaises(ValueError):
                updates.candidate("1.0.322")
            request.assert_not_called()
        response = MagicMock()
        response.__enter__.return_value.status = 200
        with patch.object(updates, "fetch_json", side_effect=[index("1.0.367.1"),
                {"tag_name": "v1.0.367", "draft": False, "prerelease": False}]), \
                patch.object(updates, "request", side_effect=[response, OSError("ARM package missing")]), \
                self.assertRaisesRegex(OSError, "ARM package missing"):
            updates.candidate("1.0.322")


class PullRequestLifecycleTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "gradle").mkdir()
        self.manifest = "# Keep this comment.\nemulatorVersion=1.0.322\n"
        (self.root / updates.MANIFEST).write_text(self.manifest)
        self.posts = []

        def github(path, payload=None):
            if payload is not None:
                self.posts.append((path, payload))
                return pull_request() if path.endswith("/pulls") else None
            if path == "repos/example/plugin":
                return {"default_branch": "main"}
            if "/branches/" in path:
                return {"commit": {"sha": "base-commit"}}
            if "/runs?" in path:
                return {"workflow_runs": []}
            raise AssertionError(path)

        def command(*args, **kwargs):
            if args == ("git", "status", "--porcelain"):
                return ""
            if args == ("git", "rev-parse", "HEAD"):
                return "base-commit"
            raise AssertionError(args)

        for patcher in (patch.dict(updates.os.environ, {"GITHUB_REPOSITORY": "example/plugin"}),
                        patch.object(updates, "command", side_effect=command),
                        patch.object(updates, "github", side_effect=github)):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_new_pr_is_draft_changes_only_manifest_and_dispatches_ci(self):
        with patch.object(updates, "github_pages", side_effect=[[], []]), \
                patch.object(updates, "candidate", return_value="1.0.367"), \
                patch.object(updates, "prepare_branch") as branch:
            updates.create_pr(self.root)
        branch.assert_called_once_with(self.root, "codex/update-aws-emulator-1.0.367",
                                       "# Keep this comment.\nemulatorVersion=1.0.367\n")
        self.assertEqual(2, len(self.posts))
        self.assertTrue(self.posts[0][1]["draft"])
        self.assertIn("- [ ] Audit CloudFormation", self.posts[0][1]["body"])
        self.assertIn("/actions/workflows/ci.yml/dispatches", self.posts[1][0])
        self.assertEqual({"ref": "codex/update-aws-emulator-1.0.367"}, self.posts[1][1])

    def test_open_pr_preserves_audit_work_and_recovers_missing_ci_dispatch(self):
        with patch.object(updates, "github_pages", return_value=[pull_request()]), \
                patch.object(updates, "candidate") as candidate, \
                patch.object(updates, "prepare_branch") as branch:
            updates.create_pr(self.root)
        candidate.assert_not_called()
        branch.assert_not_called()
        self.assertEqual(1, len(self.posts))
        self.assertTrue(self.posts[0][0].endswith("/dispatches"))

    def test_closed_pr_is_not_reopened_or_replaced(self):
        with patch.object(updates, "github_pages", side_effect=[[], [pull_request()]]), \
                patch.object(updates, "candidate", return_value="1.0.367"), \
                patch.object(updates, "prepare_branch") as branch:
            updates.create_pr(self.root)
        branch.assert_not_called()
        self.assertEqual([], self.posts)

    def test_current_version_creates_nothing(self):
        with patch.object(updates, "github_pages", return_value=[]), \
                patch.object(updates, "candidate", return_value=None), \
                patch.object(updates, "prepare_branch") as branch:
            updates.create_pr(self.root)
        branch.assert_not_called()
        self.assertEqual([], self.posts)

    def test_dirty_or_stale_checkout_is_rejected(self):
        with patch.object(updates, "command", return_value=" M important-file"), \
                self.assertRaisesRegex(ValueError, "clean checkout"):
            updates.create_pr(self.root)
        with patch.object(updates, "github_pages", return_value=[]), \
                patch.object(updates, "command", side_effect=["", "old-commit"]), \
                self.assertRaisesRegex(ValueError, "current default-branch"):
            updates.create_pr(self.root)
        self.assertEqual([], self.posts)


class DispatchTest(unittest.TestCase):
    def test_same_commit_is_not_dispatched_again_even_if_checks_failed(self):
        with patch.object(updates, "github", return_value={"workflow_runs": [
            {"head_sha": "candidate-commit", "conclusion": "failure"},
        ]}) as github:
            updates.ensure_ci("example/plugin", pull_request())
        self.assertEqual(1, github.call_count)

    def test_new_review_commit_gets_its_own_checks(self):
        with patch.object(updates, "github", side_effect=[{"workflow_runs": [{"head_sha": "older-commit"}]}, None]) as github:
            updates.ensure_ci("example/plugin", pull_request())
        self.assertEqual(2, github.call_count)

    def test_never_dispatches_an_unrelated_branch(self):
        for repository, branch in (("other/repo", "codex/update-aws-emulator-1.0.367"), ("example/plugin", "main")):
            pr = pull_request()
            pr["head"]["repo"]["full_name"] = repository
            pr["head"]["ref"] = branch
            with self.subTest(repository=repository, branch=branch), \
                    patch.object(updates, "github") as github, self.assertRaises(ValueError):
                updates.ensure_ci("example/plugin", pr)
            github.assert_not_called()


class BranchRecoveryTest(unittest.TestCase):
    def test_reuses_only_a_branch_containing_the_expected_version_change(self):
        with patch.object(updates, "command", side_effect=["abc refs/heads/candidate", "", "", updates.MANIFEST,
                                                         "emulatorVersion=1.0.367"]) as command:
            updates.prepare_branch(Path("unused"), "candidate", "emulatorVersion=1.0.367\n")
        self.assertFalse(any("push" in call.args or "switch" in call.args for call in command.call_args_list))

    def test_never_overwrites_other_work_on_a_recovered_branch(self):
        for paths, content in (("important-file", "emulatorVersion=1.0.367"),
                               (updates.MANIFEST, "emulatorVersion=1.0.366")):
            with self.subTest(paths=paths, content=content), \
                    patch.object(updates, "command", side_effect=["abc refs/heads/candidate", "", "", paths, content]), \
                    self.assertRaisesRegex(ValueError, "contains other work"):
                updates.prepare_branch(Path("unused"), "candidate", "emulatorVersion=1.0.367\n")


if __name__ == "__main__":
    unittest.main()
