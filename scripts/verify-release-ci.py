#!/usr/bin/env python3
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

"""Require successful main CI for the exact checked-out release commit."""

import argparse
import importlib.util
import os
from pathlib import Path
import re
import sys


spec = importlib.util.spec_from_file_location("release_driver", Path(__file__).with_name("release.py"))
driver = importlib.util.module_from_spec(spec)
exec(compile(spec.loader.get_source(spec.name), str(spec.origin), "exec"), driver.__dict__)


def verify_ci(root, repository, commit, *, timeout=3600):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("GITHUB_REPOSITORY must identify the release repository as owner/name.")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("GITHUB_SHA must identify the full release commit.")
    if timeout <= 0:
        raise ValueError("Timeout must be greater than zero.")
    if driver.command("git", "rev-parse", "HEAD", root=root) != commit:
        raise ValueError("The checked-out commit does not match GITHUB_SHA.")
    if driver.command("git", "status", "--porcelain", "--untracked-files=all", root=root):
        raise ValueError("Release CI verification requires a clean checkout.")
    origin = driver.repository_name(driver.command("git", "remote", "get-url", "origin", root=root))
    if origin.casefold() != repository.casefold():
        raise ValueError("The checkout's origin does not match GITHUB_REPOSITORY.")
    # Reuse the release command's main/push/ci.yml query and exact-SHA check.
    driver.wait_for_ci(root, repository, commit, timeout)
    print(f"Verified successful main CI for {commit}.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=int, default=3600, help="Maximum seconds to wait for CI (default: 3600).")
    args = parser.parse_args()
    try:
        verify_ci(Path(__file__).resolve().parent.parent, os.environ.get("GITHUB_REPOSITORY", ""),
                  os.environ.get("GITHUB_SHA", ""), timeout=args.timeout)
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Release CI verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
