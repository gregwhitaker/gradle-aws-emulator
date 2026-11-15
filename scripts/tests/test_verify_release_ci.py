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

"""Check that publication only reuses successful CI for the checked-out source."""

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


spec = importlib.util.spec_from_file_location("verify_release_ci", Path(__file__).parents[1] / "verify-release-ci.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ReleaseCiTest(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
                                              "GIT_TERMINAL_PROMPT": "0"})
        environment.start()
        self.addCleanup(environment.stop)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Release Test")
        self.git("config", "user.email", "release@example.invalid")
        self.git("remote", "add", "origin", "https://github.com/example/plugin.git")
        (self.root / "source.txt").write_text("Tested source.\n", encoding="utf-8")
        self.git("add", "source.txt")
        self.git("commit", "-m", "Tested release")
        self.commit = self.git("rev-parse", "HEAD")
        self.runs = [self.ci_run()]
        self.gh_calls = []
        actual_command = guard.driver.command

        def command(*args, root, raw=False):
            if args[0] == "gh":
                self.gh_calls.append(args)
                self.assertEqual(("gh", "run", "list", "--repo", "example/plugin", "--workflow", "ci.yml",
                                  "--branch", "main", "--event", "push", "--commit", self.commit,
                                  "--limit", "1", "--json", "headSha,status,conclusion,url"), args)
                return json.dumps(self.runs() if callable(self.runs) else self.runs)
            return actual_command(*args, root=root, raw=raw)

        patcher = patch.object(guard.driver, "command", side_effect=command)
        patcher.start()
        self.addCleanup(patcher.stop)
        output = contextlib.redirect_stdout(io.StringIO())
        output.__enter__()
        self.addCleanup(output.__exit__, None, None, None)

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, text=True, encoding="utf-8",
                              capture_output=True, check=True).stdout.strip()

    def ci_run(self, *, commit=None, status="completed", conclusion="success"):
        return {"headSha": commit or self.commit, "status": status, "conclusion": conclusion,
                "url": "https://github.com/example/plugin/actions/runs/1"}

    def verify(self, **kwargs):
        guard.verify_ci(self.root, "example/plugin", self.commit, **kwargs)

    def test_accepts_detached_release_only_after_successful_main_ci(self):
        self.git("tag", "v0.2.0")
        self.git("checkout", "--detach", "v0.2.0")
        self.verify()
        self.assertEqual(1, len(self.gh_calls))
        self.assertEqual(self.commit, self.git("rev-parse", "HEAD"))
        self.assertEqual("", self.git("status", "--porcelain"))
        self.assertEqual("v0.2.0", self.git("tag", "--list"))

    def test_rejects_other_checkout_commit_before_consulting_ci(self):
        with self.assertRaisesRegex(ValueError, "does not match GITHUB_SHA"):
            guard.verify_ci(self.root, "example/plugin", "0" * 40)
        self.assertEqual([], self.gh_calls)

    def test_rejects_other_repository_before_consulting_ci(self):
        with self.assertRaisesRegex(ValueError, "does not match GITHUB_REPOSITORY"):
            guard.verify_ci(self.root, "someone/else", self.commit)
        self.assertEqual([], self.gh_calls)

    def test_rejects_modified_and_untracked_source_before_consulting_ci(self):
        for name in ("source.txt", "new-source.txt"):
            with self.subTest(name=name):
                path = self.root / name
                path.write_text("Not tested.\n", encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "clean checkout"):
                    self.verify()
                if name == "source.txt":
                    self.git("checkout", "--", name)
                else:
                    path.unlink()
        self.assertEqual([], self.gh_calls)

    def test_rejects_missing_or_invalid_release_context(self):
        for repository, commit, timeout in (("", self.commit, 1), ("example/plugin/extra", self.commit, 1),
                                            ("example/plugin", "", 1), ("example/plugin", self.commit[:7], 1),
                                            ("example/plugin", self.commit, 0)):
            with self.subTest(repository=repository, commit=commit, timeout=timeout), self.assertRaises(ValueError):
                guard.verify_ci(self.root, repository, commit, timeout=timeout)
        self.assertEqual([], self.gh_calls)

    def test_success_for_a_different_commit_cannot_authorize_publication(self):
        self.runs = [self.ci_run(commit="0" * 40)]
        with patch.object(guard.driver.time, "monotonic", side_effect=[0, 2]), self.assertRaisesRegex(ValueError, "Timed out"):
            self.verify(timeout=1)

    def test_failed_cancelled_or_skipped_ci_blocks_publication(self):
        for conclusion in ("failure", "cancelled", "skipped", "timed_out", "neutral"):
            self.runs = [self.ci_run(conclusion=conclusion)]
            with self.subTest(conclusion=conclusion), self.assertRaisesRegex(ValueError, f"CI finished with {conclusion}"):
                self.verify()

    def test_waits_for_the_matching_ci_run_to_finish_successfully(self):
        states = iter([[], [self.ci_run(status="queued", conclusion=None)],
                       [self.ci_run(status="in_progress", conclusion=None)], [self.ci_run()]])
        self.runs = lambda: next(states)
        with patch.object(guard.driver.time, "sleep") as sleep:
            self.verify()
        self.assertEqual(4, len(self.gh_calls))
        self.assertEqual(3, sleep.call_count)

    def test_missing_ci_cannot_authorize_publication(self):
        self.runs = []
        with patch.object(guard.driver.time, "monotonic", side_effect=[0, 2]), self.assertRaisesRegex(ValueError, "Timed out"):
            self.verify(timeout=1)

    def test_api_failure_cannot_authorize_publication(self):
        def unavailable():
            raise RuntimeError("GitHub API unavailable")
        self.runs = unavailable
        with self.assertRaisesRegex(RuntimeError, "GitHub API unavailable"):
            self.verify()

    def test_cli_without_release_context_fails(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(guard.sys, "argv", [str(spec.origin)]), \
                contextlib.redirect_stderr(io.StringIO()) as error:
            self.assertEqual(1, guard.main())
        self.assertIn("GITHUB_REPOSITORY", error.getvalue())


if __name__ == "__main__":
    unittest.main()
