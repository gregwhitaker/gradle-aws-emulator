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

"""Exercise release preparation and recovery with real Git repositories, without network access."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("release_driver", Path(__file__).parents[1] / "release.py")
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)

PROPERTIES = "# Keep this comment.\npluginVersion=0.2.0\notherProperty=unchanged\n"
CHANGELOG = "# Changelog\n\n## 0.2.0 — Unreleased\n\n- New feature.\n\n## 0.1.0 — 2026-10-01\n\n- Original release.\n"


class MetadataTest(unittest.TestCase):
    def test_updates_only_version_and_top_heading(self):
        properties, changelog, resume = driver.prepare_metadata(PROPERTIES, CHANGELOG, "0.3.0", "2026-11-14")
        self.assertEqual(PROPERTIES.replace("pluginVersion=0.2.0", "pluginVersion=0.3.0"), properties)
        self.assertEqual(CHANGELOG.replace("## 0.2.0 — Unreleased", "## 0.3.0 — 2026-11-14"), changelog)
        self.assertFalse(resume)
        self.assertEqual("0.3.0", driver.guard.verify_release(properties, changelog, "tag", "v0.3.0"))

    def test_generic_unreleased_and_prerelease(self):
        properties, changelog, resume = driver.prepare_metadata(
            PROPERTIES, CHANGELOG.replace("0.2.0 — Unreleased", "Unreleased"), "0.2.0-rc.1", "2026-11-14")
        self.assertFalse(resume)
        self.assertEqual("0.2.0-rc.1", driver.guard.verify_release(properties, changelog, "tag", "v0.2.0-rc.1"))

    def test_preserves_crlf_and_original_date_when_resuming(self):
        original = [text.replace("\n", "\r\n") for text in (PROPERTIES, CHANGELOG)]
        properties, changelog, _ = driver.prepare_metadata(*original, "0.2.0", "2026-11-14")
        self.assertEqual(original[0], properties)
        self.assertEqual(original[1].replace("Unreleased", "2026-11-14"), changelog)
        self.assertEqual((properties, changelog, True), driver.prepare_metadata(properties, changelog, "0.2.0", "2026-11-15"))

    def test_rejects_invalid_or_older_version_and_invalid_date(self):
        for version in ("0.1.9", "0.3.0-SNAPSHOT", "0.3.0+build", "v0.3.0", "0.3.0-rc.01", "0.3.0\nother=x"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                driver.prepare_metadata(PROPERTIES, CHANGELOG, version, "2026-11-14")
        with self.assertRaises(ValueError):
            driver.prepare_metadata(PROPERTIES, CHANGELOG, "0.2.0", "2026-02-30")

    def test_rejects_missing_or_ambiguous_metadata_without_rewriting_history(self):
        for properties, changelog in (
            (PROPERTIES + "pluginVersion=0.2.0\n", CHANGELOG),
            (PROPERTIES, "# Changelog\n"),
            (PROPERTIES, CHANGELOG.replace(" — Unreleased", " — 2026-11-01")),
            (PROPERTIES, CHANGELOG.replace("- New feature.", "")),
            (PROPERTIES, CHANGELOG.replace("## 0.1.0", "## 0.3.0")),
        ):
            with self.subTest(properties=properties, changelog=changelog), self.assertRaises(ValueError):
                driver.prepare_metadata(properties, changelog, "0.3.0", "2026-11-14")

    def test_repository_url_forms(self):
        for url in ("https://github.com/example/plugin.git", "git@github.com:example/plugin.git",
                    "ssh://git@github.com/example/plugin.git", "https://github.com/example/plugin"):
            self.assertEqual("example/plugin", driver.repository_name(url))
        for url in ("https://example.com/example/plugin", "/tmp/repository", "https://github.com/example/plugin/extra"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                driver.repository_name(url)


class ReleaseGitTest(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_TERMINAL_PROMPT": "0"})
        environment.start()
        self.addCleanup(environment.stop)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        self.root = directory / "checkout"
        self.remote = directory / "origin.git"
        self.root.mkdir()
        self.run_git("init", "--initial-branch=main")
        self.run_git("config", "user.name", "Release Test")
        self.run_git("config", "user.email", "release@example.invalid")
        self.run_git("config", "core.autocrlf", "false")
        self.run_git("config", "push.followTags", "true")
        self.run_git("init", "--bare", str(self.remote))
        self.run_git("remote", "add", "origin", str(self.remote))
        (self.root / "gradle.properties").write_text(PROPERTIES, encoding="utf-8")
        (self.root / "CHANGELOG.md").write_text(CHANGELOG, encoding="utf-8")
        (self.root / "README.md").write_text("Unrelated content.\n", encoding="utf-8")
        self.run_git("add", ".")
        self.run_git("commit", "-m", "Initial commit")
        self.run_git("push", "origin", "main")
        self.initial = self.run_git("rev-parse", "HEAD")
        self.ci_status = "success"
        self.fail_push = None
        self.fail_auth = False
        self.gh_calls = []
        self.ci_callback = None
        actual_command = driver.command

        def command(*args, root, raw=False):
            if args[:3] == ("git", "remote", "get-url"):
                # The actual fetches/pushes still use the local bare repository.
                return "https://github.com/example/plugin.git"
            if args[0] == "gh":
                self.gh_calls.append(args)
                if args[1:3] == ("auth", "status"):
                    if self.fail_auth:
                        raise RuntimeError("GitHub authentication failed")
                    return ""
                if args[1:3] == ("run", "list"):
                    commit = args[args.index("--commit") + 1]
                    self.assertEqual(commit, self.remote_git("rev-parse", "refs/heads/main"))
                    if self.ci_callback:
                        return json.dumps(self.ci_callback(commit))
                    return json.dumps([self.ci_run(commit, self.ci_status)])
                raise AssertionError(args)
            if args[:2] == ("git", "push") and self.fail_push and self.fail_push in args[-1]:
                raise RuntimeError("Simulated push rejection")
            return actual_command(*args, root=root, raw=raw)

        patcher = patch.object(driver, "command", side_effect=command)
        patcher.start()
        self.addCleanup(patcher.stop)
        output = contextlib.redirect_stdout(io.StringIO())
        output.__enter__()
        self.addCleanup(output.__exit__, None, None, None)

    def run_git(self, *args):
        result = subprocess.run(["git", *args], cwd=self.root, text=True, encoding="utf-8", capture_output=True, check=True)
        return result.stdout.strip()

    def remote_git(self, *args):
        return self.run_git("--git-dir", str(self.remote), *args)

    def ci_run(self, commit, conclusion):
        return {"headSha": commit, "status": "completed", "conclusion": conclusion,
                "url": "https://github.com/example/plugin/actions/runs/1"}

    def release(self, version="0.3.0", **kwargs):
        driver.release(self.root, version, date="2026-11-14", **kwargs)

    def assert_untouched(self):
        self.assertEqual(self.initial, self.run_git("rev-parse", "HEAD"))
        self.assertEqual(self.initial, self.remote_git("rev-parse", "refs/heads/main"))
        self.assertEqual("", self.run_git("tag", "--list"))
        self.assertEqual(PROPERTIES, (self.root / "gradle.properties").read_text())
        self.assertEqual(CHANGELOG, (self.root / "CHANGELOG.md").read_text())

    def test_commits_metadata_and_pushes_only_release_tag_after_ci(self):
        self.run_git("tag", "-a", "unrelated", "-m", "Keep local")
        self.release()
        commit = self.run_git("rev-parse", "HEAD")
        self.assertEqual("Release 0.3.0", self.run_git("log", "-1", "--format=%s"))
        self.assertEqual(self.initial, self.run_git("rev-parse", "HEAD^"))
        self.assertEqual(set(driver.FILES), set(self.run_git("diff", "--name-only", "HEAD^").splitlines()))
        self.assertEqual(commit, self.remote_git("rev-parse", "refs/tags/v0.3.0^{commit}"))
        self.assertEqual("tag", self.run_git("cat-file", "-t", "refs/tags/v0.3.0"))
        self.assertEqual("v0.3.0", self.remote_git("tag", "--list"))
        self.assertEqual("", self.run_git("status", "--porcelain"))

    def test_first_release_can_use_already_planned_version(self):
        self.release("0.2.0")
        self.assertEqual("CHANGELOG.md", self.run_git("diff", "--name-only", "HEAD^"))
        self.assertEqual(self.run_git("rev-parse", "HEAD"), self.remote_git("rev-parse", "refs/tags/v0.2.0^{commit}"))

    def test_dry_run_changes_no_files_commits_or_refs(self):
        self.release(dry_run=True)
        self.assert_untouched()
        self.assertEqual([], self.gh_calls)
        self.assertEqual("", self.run_git("status", "--porcelain"))

    def test_dirty_and_staged_changes_are_preserved(self):
        (self.root / "README.md").write_text("My work.\n")
        for staged in (False, True):
            if staged:
                self.run_git("add", "README.md")
            with self.subTest(staged=staged), self.assertRaisesRegex(ValueError, "clean checkout"):
                self.release()
            self.assert_untouched()
            self.assertEqual("My work.\n", (self.root / "README.md").read_text())

    def test_untracked_work_is_preserved(self):
        (self.root / "notes.txt").write_text("Keep me.")
        with self.assertRaisesRegex(ValueError, "clean checkout"):
            self.release()
        self.assert_untouched()
        self.assertEqual("Keep me.", (self.root / "notes.txt").read_text())

    def test_requires_main_and_rejects_unpushed_nonrelease_commits(self):
        self.run_git("switch", "-c", "feature")
        with self.assertRaisesRegex(ValueError, "from main"):
            self.release()
        self.assert_untouched()
        self.run_git("switch", "main")
        self.run_git("commit", "--allow-empty", "-m", "Unpushed work")
        commit = self.run_git("rev-parse", "HEAD")
        with self.assertRaisesRegex(ValueError, "origin/main"):
            self.release()
        self.assertEqual(commit, self.run_git("rev-parse", "HEAD"))
        self.assertEqual(PROPERTIES, (self.root / "gradle.properties").read_text())

    def test_authentication_fails_before_metadata_is_changed(self):
        self.fail_auth = True
        with self.assertRaisesRegex(RuntimeError, "authentication"):
            self.release()
        self.assert_untouched()

    def test_missing_git_identity_fails_before_metadata_is_changed(self):
        self.run_git("config", "--unset", "user.email")
        self.run_git("config", "user.useConfigOnly", "true")
        with self.assertRaisesRegex(RuntimeError, "git var failed"):
            self.release()
        self.assert_untouched()

    def test_existing_local_or_remote_tag_is_never_overwritten(self):
        self.run_git("tag", "v0.3.0")
        with self.assertRaisesRegex(ValueError, "Local tag"):
            self.release()
        self.run_git("push", "origin", "refs/tags/v0.3.0")
        self.run_git("tag", "-d", "v0.3.0")
        with self.assertRaisesRegex(ValueError, "Remote tag"):
            self.release()
        self.assertEqual(self.initial, self.remote_git("rev-parse", "refs/tags/v0.3.0"))
        self.assert_untouched()

    def test_ci_failure_never_tags_and_rerun_preserves_commit_and_date(self):
        self.ci_status = "failure"
        with self.assertRaisesRegex(ValueError, "CI finished with failure"):
            self.release()
        commit = self.run_git("rev-parse", "HEAD")
        self.assertNotEqual(self.initial, commit)
        self.assertEqual("", self.run_git("tag", "--list"))
        self.assertEqual("", self.remote_git("tag", "--list"))
        self.ci_status = "success"
        driver.release(self.root, "0.3.0", date="2026-11-15")
        self.assertEqual(commit, self.run_git("rev-parse", "HEAD"))
        self.assertIn("## 0.3.0 — 2026-11-14", (self.root / "CHANGELOG.md").read_text())
        self.assertEqual(commit, self.remote_git("rev-parse", "refs/tags/v0.3.0^{commit}"))

    def test_waits_for_matching_commit_success_before_tagging(self):
        calls = []

        def ci(commit):
            self.assertEqual("", self.remote_git("tag", "--list"))
            self.assertEqual("", self.run_git("tag", "--list"))
            calls.append(commit)
            if len(calls) == 1:
                return [self.ci_run(self.initial, "success")]
            if len(calls) == 2:
                return [{**self.ci_run(commit, None), "status": "in_progress"}]
            return [self.ci_run(commit, "success")]

        self.ci_callback = ci
        with patch.object(driver.time, "sleep"):
            self.release()
        self.assertEqual(3, len(calls))

    def test_timeout_leaves_no_tag(self):
        self.ci_callback = lambda commit: []
        with patch.object(driver.time, "monotonic", side_effect=[0, 2]), self.assertRaisesRegex(ValueError, "Timed out"):
            self.release(timeout=1)
        self.assertEqual("", self.run_git("tag", "--list"))
        self.assertEqual("", self.remote_git("tag", "--list"))

    def test_resumes_after_branch_push_failure(self):
        self.fail_push = "refs/heads/main"
        with self.assertRaisesRegex(RuntimeError, "push rejection"):
            self.release()
        commit = self.run_git("rev-parse", "HEAD")
        self.assertEqual(self.initial, self.remote_git("rev-parse", "refs/heads/main"))
        self.fail_push = None
        self.release()
        self.assertEqual(commit, self.run_git("rev-parse", "HEAD"))
        self.assertEqual(commit, self.remote_git("rev-parse", "refs/tags/v0.3.0^{commit}"))

    def test_resumes_after_tag_push_failure_and_completed_release_is_noop(self):
        self.fail_push = "refs/tags/"
        with self.assertRaisesRegex(RuntimeError, "push rejection"):
            self.release()
        tag_object = self.run_git("rev-parse", "refs/tags/v0.3.0")
        self.assertEqual("", self.remote_git("tag", "--list"))
        self.fail_push = None
        self.release()
        self.assertEqual(tag_object, self.remote_git("rev-parse", "refs/tags/v0.3.0"))
        self.gh_calls.clear()
        self.release()
        self.assertEqual([], self.gh_calls)
        self.assertEqual(tag_object, self.remote_git("rev-parse", "refs/tags/v0.3.0"))

    def test_refuses_to_resume_release_commit_with_other_changes(self):
        self.ci_status = "failure"
        with self.assertRaises(ValueError):
            self.release()
        (self.root / "README.md").write_text("Extra change.\n")
        self.run_git("add", "README.md")
        self.run_git("commit", "--amend", "--no-edit")
        commit = self.run_git("rev-parse", "HEAD")
        with self.assertRaisesRegex(ValueError, "metadata-only"):
            self.release()
        self.assertEqual(commit, self.run_git("rev-parse", "HEAD"))
        self.assertEqual("", self.remote_git("tag", "--list"))

    def test_refuses_tag_changed_during_resumed_ci_wait(self):
        self.fail_push = "refs/tags/"
        with self.assertRaises(RuntimeError):
            self.release()
        self.fail_push = None

        def ci(commit):
            self.run_git("tag", "-f", "v0.3.0", self.initial)
            return [self.ci_run(commit, "success")]

        self.ci_callback = ci
        with self.assertRaisesRegex(ValueError, "changed while CI ran"):
            self.release()
        self.assertEqual("", self.remote_git("tag", "--list"))

    def test_remote_tag_created_during_ci_is_not_overwritten(self):
        def ci(commit):
            self.remote_git("update-ref", "refs/tags/v0.3.0", self.initial)
            return [self.ci_run(commit, "success")]

        self.ci_callback = ci
        with self.assertRaisesRegex(RuntimeError, "git push failed"):
            self.release()
        self.assertEqual(self.initial, self.remote_git("rev-parse", "refs/tags/v0.3.0"))


if __name__ == "__main__":
    unittest.main()
