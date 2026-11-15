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

"""Allow public documentation only for the latest published stable plugin release."""

import argparse
import importlib.util
import os
from pathlib import Path
import re
import sys
from urllib.error import URLError
from urllib.request import Request, urlopen


spec = importlib.util.spec_from_file_location("publish_github_release", Path(__file__).with_name("publish-github-release.py"))
release = importlib.util.module_from_spec(spec)
exec(compile(spec.loader.get_source(spec.name), str(spec.origin), "exec"), release.__dict__)


def verify_portal(version):
    """Check both public plugin markers and their implementation artifacts."""
    for plugin_id, artifact in (
        ("io.github.gregwhitaker.aws-emulator", "gradle-aws-emulator"),
        ("io.github.gregwhitaker.aws-emulator.spring-boot", "gradle-aws-emulator-spring-boot"),
    ):
        marker = f"{plugin_id}.gradle.plugin"
        paths = [f"{plugin_id.replace('.', '/')}/{marker}/{version}/{marker}-{version}.pom"]
        paths.extend(f"io/github/gregwhitaker/{artifact}/{version}/{artifact}-{version}.{extension}"
                     for extension in ("pom", "jar"))
        for path in paths:
            url = f"https://plugins.gradle.org/m2/{path}"
            request = Request(url, method="HEAD", headers={"User-Agent": "gradle-aws-emulator-docs"})
            try:
                with urlopen(request, timeout=30) as response:
                    if response.status != 200:
                        raise RuntimeError(f"Plugin Portal returned HTTP {response.status} for {url}.")
            except URLError as error:
                raise RuntimeError(
                    f"Plugin Portal artifact is not publicly available: {url}. "
                    "Wait for publication or approval, then rerun documentation deployment."
                ) from error


def verify_docs_release(github, tag, commit):
    number = r"(?:0|[1-9][0-9]*)"
    if not re.fullmatch(rf"v{number}\.{number}\.{number}", tag):
        raise ValueError("Documentation requires a stable release tag such as v0.2.0.")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Documentation requires the full release commit SHA.")
    published = github.request("GET", f"releases/tags/{tag}")
    if (published["tag_name"] != tag or published["draft"] or published["prerelease"]
            or not published["published_at"]):
        raise ValueError(f"{tag} must have a published, stable GitHub Release before deploying documentation.")
    latest = github.request("GET", "releases/latest")
    if latest["tag_name"] != tag:
        raise ValueError(f"Only the latest stable release ({latest['tag_name']}) can deploy public documentation.")
    release.verify_tag(github, tag, commit)
    verify_portal(tag[1:])
    return tag[1:]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""))
    args = parser.parse_args()
    try:
        github = release.GitHub(args.repository, os.environ.get("GH_TOKEN", ""))
        version = verify_docs_release(github, args.tag, args.commit)
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Documentation release verification failed: {error}", file=sys.stderr)
        return 1
    print(f"Documentation release verified: v{version} at {args.commit}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
