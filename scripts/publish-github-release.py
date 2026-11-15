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

"""Publish GitHub release notes after both plugins have been uploaded successfully."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen


spec = importlib.util.spec_from_file_location("verify_release", Path(__file__).with_name("verify-release.py"))
guard = importlib.util.module_from_spec(spec)
exec(compile(spec.loader.get_source(spec.name), str(spec.origin), "exec"), guard.__dict__)


class GitHub:
    def __init__(self, repository, token):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("GITHUB_REPOSITORY must be an owner/repository name.")
        if not token:
            raise ValueError("GH_TOKEN must grant contents access to the repository.")
        self.base_url = f"https://api.github.com/repos/{repository}"
        self.token = token

    def request(self, method, path, data=None, missing_ok=False):
        request = Request(
            f"{self.base_url}/{path}",
            data=json.dumps(data).encode("utf-8") if data is not None else None,
            method=method,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
                "User-Agent": "gradle-aws-emulator-release",
                "X-GitHub-Api-Version": "2026-03-10",
            },
        )
        try:
            with urlopen(request, timeout=30) as response:
                return json.load(response)
        except HTTPError as error:
            if method == "GET" and missing_ok and error.code == 404:
                return None
            raise RuntimeError(f"GitHub {method} {path} failed (HTTP {error.code}).") from error


def release_notes(properties, changelog, ref_type, ref_name):
    version = guard.verify_release(properties, changelog, ref_type, ref_name)
    headings = list(re.finditer(r"^## [^\r\n]+\r?$", changelog, re.MULTILINE))
    end = headings[1].start() if len(headings) > 1 else len(changelog)
    notes = changelog[headings[0].end():end].strip()
    if not notes:
        raise ValueError("The dated changelog section must contain release notes.")
    return version, notes


def verify_tag(github, tag, commit):
    """Resolve lightweight or annotated tags without creating or moving a ref."""
    target = github.request("GET", f"git/ref/tags/{quote(tag, safe='')}")["object"]
    for _ in range(10):
        if target["type"] == "commit":
            if target["sha"] != commit:
                raise ValueError(f"Tag {tag} no longer points to the checked-out release commit.")
            return
        if target["type"] != "tag":
            break
        target = github.request("GET", f"git/tags/{target['sha']}")["object"]
    raise ValueError(f"Tag {tag} does not resolve to a commit.")


def find_release(github, tag):
    existing = github.request("GET", f"releases/tags/{quote(tag, safe='')}", missing_ok=True)
    if existing is not None:
        return existing
    # The tag endpoint only returns published releases. Authenticated listing
    # also includes drafts, including those prepared before the tag was pushed.
    page = 1
    while True:
        releases = github.request("GET", f"releases?per_page=100&page={page}")
        for candidate in releases:
            if candidate["tag_name"] == tag:
                return candidate
        if len(releases) < 100:
            return None
        page += 1


def publish_release(github, version, notes, commit):
    tag = f"v{version}"
    verify_tag(github, tag, commit)
    existing = find_release(github, tag)
    prerelease = "-" in version
    if existing is not None:
        if existing["draft"]:
            # Publish a prepared draft while preserving its title, notes, and assets.
            existing = github.request("PATCH", f"releases/{existing['id']}", {
                "draft": False,
                "prerelease": prerelease,
            })
        return existing["html_url"]
    created = github.request("POST", "releases", {
        "tag_name": tag,
        "target_commitish": commit,
        "name": tag,
        "body": notes,
        "draft": False,
        "prerelease": prerelease,
    })
    return created["html_url"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--ref-type", default=os.environ.get("GITHUB_REF_TYPE", ""))
    parser.add_argument("--ref-name", default=os.environ.get("GITHUB_REF_NAME", ""))
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""))
    args = parser.parse_args()
    try:
        version, notes = release_notes(
            (args.root / "gradle.properties").read_text(encoding="utf-8"),
            (args.root / "CHANGELOG.md").read_text(encoding="utf-8"),
            args.ref_type, args.ref_name,
        )
        commit = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=args.root, text=True, capture_output=True, check=True,
        ).stdout.strip()
        github = GitHub(args.repository, os.environ.get("GH_TOKEN", ""))
        url = publish_release(github, version, notes, commit)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"GitHub release failed: {error}", file=sys.stderr)
        return 1
    print(f"GitHub release published: {url}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
