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

"""Validate release metadata without contacting the Plugin Portal."""

import argparse
import datetime
import os
from pathlib import Path
import re
import sys


def verify_release(properties, changelog, ref_type, ref_name):
    versions = re.findall(r"^pluginVersion=(.*)$", properties, re.MULTILINE)
    if len(versions) != 1:
        raise ValueError("gradle.properties must contain exactly one pluginVersion entry.")
    version = versions[0].rstrip("\r")
    number = r"(?:0|[1-9][0-9]*)"
    identifier = rf"(?:{number}|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
    semver = rf"{number}\.{number}\.{number}(?:-{identifier}(?:\.{identifier})*)?"
    if not re.fullmatch(semver, version) or "SNAPSHOT" in version.upper():
        raise ValueError("pluginVersion must be MAJOR.MINOR.PATCH with an optional prerelease, without SNAPSHOT or build metadata.")
    if ref_type != "tag" or ref_name != f"v{version}":
        raise ValueError(f"Publish from tag v{version}, matching pluginVersion in gradle.properties.")
    headings = re.findall(r"^## (.+)$", changelog, re.MULTILINE)
    if not headings:
        raise ValueError("CHANGELOG.md must start with the release's dated section.")
    match = re.fullmatch(rf"{re.escape(version)} — (\d{{4}}-\d{{2}}-\d{{2}})\r?", headings[0])
    if not match:
        raise ValueError(f"The first changelog section must be '## {version} — YYYY-MM-DD', not Unreleased.")
    try:
        datetime.date.fromisoformat(match[1])
    except ValueError as error:
        raise ValueError("The changelog release date must be a valid calendar date.") from error
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--ref-type", default=os.environ.get("GITHUB_REF_TYPE", ""))
    parser.add_argument("--ref-name", default=os.environ.get("GITHUB_REF_NAME", ""))
    args = parser.parse_args()
    try:
        version = verify_release(
            (args.root / "gradle.properties").read_text(encoding="utf-8"),
            (args.root / "CHANGELOG.md").read_text(encoding="utf-8"),
            args.ref_type, args.ref_name,
        )
    except (OSError, ValueError) as error:
        print(f"Release verification failed: {error}", file=sys.stderr)
        return 1
    print(f"Release metadata verified for v{version}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
