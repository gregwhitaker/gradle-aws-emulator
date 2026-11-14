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

"""Check published AWS Emulator packages, or open one draft upgrade PR for human review."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.parse
import urllib.request


PACKAGES = "https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS"
UPSTREAM = "https://github.com/aws/aws-emulator"
MANIFEST = "gradle/emulator.properties"
BRANCH_PREFIX = "codex/update-aws-emulator-"
NUMBER = r"(?:0|[1-9][0-9]*)"
VERSION = rf"{NUMBER}\.{NUMBER}\.{NUMBER}"


def read_version(text):
    values = re.findall(r"^emulatorVersion=(.*)$", text, re.MULTILINE)
    if len(values) != 1 or not re.fullmatch(VERSION, values[0].rstrip("\r")):
        raise ValueError(f"{MANIFEST} must contain exactly one emulatorVersion=MAJOR.MINOR.PATCH entry.")
    return values[0].rstrip("\r")


def version_key(version):
    return tuple(int(part) for part in version.split("."))


def latest_version(index):
    if not isinstance(index, dict) or not isinstance(index.get("versions"), list):
        raise ValueError("Unexpected AWS published-version index; expected a versions list.")
    versions = []
    for entry in index["versions"]:
        if not isinstance(entry, dict) or entry.get("productId") != "aws-emulator" or entry.get("releaseStream") != "LTS":
            continue
        value = entry.get("version", "")
        if not isinstance(value, str) or not re.fullmatch(rf"{VERSION}(?:\.{NUMBER})?", value):
            continue  # Never propose prerelease or nonnumeric versions.
        parts = version_key(value)
        versions.append(parts if len(parts) == 4 else (*parts, 1))
    if not versions:
        raise ValueError("No stable AWS Emulator LTS versions found in the published-version index.")
    latest = max(versions)
    # AWS's index includes the package revision, e.g. 1.0.367.1 means 1.0.367-1.
    # DockerImageBuilder currently installs revision 1. Do not silently select an older package.
    if latest[3] != 1:
        raise ValueError(f"Latest AWS package revision is {latest[3]}; review DockerImageBuilder before upgrading.")
    return ".".join(str(part) for part in latest[:3])


def request(url, method="GET"):
    return urllib.request.urlopen(urllib.request.Request(url, method=method, headers={
        "User-Agent": "gradle-aws-emulator-updater",
    }), timeout=30)


def fetch_json(url):
    with request(url) as response:
        return json.load(response)


def candidate(current):
    latest = latest_version(fetch_json(f"{PACKAGES}/versions.json"))
    if version_key(latest) <= version_key(current):
        print(f"AWS Emulator {current} is up to date (latest published: {latest}).")
        return None
    release = fetch_json(f"https://api.github.com/repos/aws/aws-emulator/releases/tags/v{latest}")
    if release.get("tag_name") != f"v{latest}" or release.get("draft") is not False or release.get("prerelease") is not False:
        raise ValueError(f"AWS Emulator {latest} does not have a published stable GitHub release yet.")
    for architecture in ("x86_64", "aarch64"):
        url = f"{PACKAGES}/aws-emulator-{latest}-1-{architecture}.rpm"
        with request(url, method="HEAD") as response:
            if response.status != 200:
                raise ValueError(f"Package is not available: {url}")
    print(f"AWS Emulator upgrade available: {current} -> {latest}; both RPM architectures are published.")
    return latest


def command(*args, root=None, input_text=None):
    result = subprocess.run(args, cwd=root, input=input_text, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"{args[0]} {args[1]} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout.strip()


def github(path, payload=None):
    args = ["gh", "api", "--method", "GET" if payload is None else "POST", path]
    if payload is not None:
        args.extend(["--input", "-"])
    output = command(*args, input_text=None if payload is None else json.dumps(payload))
    return json.loads(output) if output else None


def github_pages(path):
    pages = json.loads(command("gh", "api", "--paginate", "--slurp", path))
    return [item for page in pages for item in page]


def ensure_ci(repository, pr):
    head = pr["head"]
    if head["repo"]["full_name"] != repository or not head["ref"].startswith(BRANCH_PREFIX):
        raise ValueError("Refusing to dispatch an upgrade check for an unrelated PR branch.")
    query = urllib.parse.urlencode({"branch": head["ref"], "event": "workflow_dispatch", "per_page": 100})
    runs = github(f"repos/{repository}/actions/workflows/ci.yml/runs?{query}")["workflow_runs"]
    if any(run["head_sha"] == head["sha"] for run in runs):
        print(f"Compatibility checks already dispatched for {head['sha']}; rerun failures from Actions after review.")
        return
    # Token-created pushes do not start push workflows. Explicit dispatch works with GITHUB_TOKEN
    # and avoids a PAT or automatic approval of pull-request workflows.
    github(f"repos/{repository}/actions/workflows/ci.yml/dispatches", {"ref": head["ref"]})
    print(f"Dispatched Build compatibility checks for {pr['html_url']}.")


def pr_body(current, latest):
    return f"""Updates the shared AWS Emulator default from `{current}` to `{latest}`.

[Release notes]({UPSTREAM}/releases/tag/v{latest}) · [Upstream changes]({UPSTREAM}/compare/v{current}...v{latest})

Both AMD64 and ARM64 RPM packages were available when this PR was opened. The bot changes only `{MANIFEST}`.
This PR stays a draft until a maintainer completes the review. The bot never approves, merges, or enables auto-merge.

## Required maintainer review

- [ ] Review release notes and behavior changes, including lifecycle, TLS, Cognito, SES, and supported service operations.
- [ ] Audit CloudFormation's native provisioning dispatch at the new upstream tag and record its commit in a new `provisioned-types-{latest}.txt` catalogue. Verify real provisioning, including dedicated handlers and declaration-only fallbacks.
- [ ] Update `CloudFormationTemplate.EMULATOR_VERSION` only after that audit; update affected validation and real-service tests.
- [ ] Update version-specific README/example/guide claims and audit references. Historical changelog entries retain their original versions.
- [ ] Require the **CloudFormation audit** and **Emulator compatibility** checks to pass, including both plugins, both Docker architectures, the supported Gradle matrix, and configuration-cache reuse.
- [ ] Obtain a maintainer's approving review before merging. Mark ready only after completing the audit.

The initial CloudFormation audit check is expected to fail: a version bump does not establish provisioning support.
See [the upgrade procedure](../blob/main/CONTRIBUTING.md#aws-emulator-upgrades) for audit steps and required branch protection.
"""


def prepare_branch(root, branch, updated):
    ref = f"refs/heads/{branch}"
    existing = command("git", "ls-remote", "origin", ref, root=root)
    if existing:
        # Recover a push that succeeded before PR creation failed, without resetting review work.
        sha = existing.split()[0]
        command("git", "fetch", "--no-tags", "origin", ref, root=root)
        command("git", "merge-base", "--is-ancestor", "HEAD", sha, root=root)
        changed = command("git", "diff", "--name-only", f"HEAD...{sha}", root=root).splitlines()
        contents = command("git", "show", f"{sha}:{MANIFEST}", root=root)
        if changed != [MANIFEST] or contents != updated.strip():
            raise ValueError(f"Existing branch {branch} contains other work; inspect it manually. It was not changed.")
        return
    command("git", "switch", "-c", branch, root=root)
    (root / MANIFEST).write_text(updated, encoding="utf-8")
    command("git", "add", "--", MANIFEST, root=root)
    command("git", "-c", "user.name=github-actions[bot]", "-c",
            "user.email=41898282+github-actions[bot]@users.noreply.github.com", "commit", "-m",
            f"Update AWS Emulator to {read_version(updated)} for compatibility review", root=root)
    command("git", "push", "origin", f"HEAD:{ref}", root=root)


def create_pr(root):
    if command("git", "status", "--porcelain", root=root):
        raise ValueError("PR creation requires a clean checkout of the default branch.")
    repository = os.environ.get("GITHUB_REPOSITORY") or command(
        "gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner", root=root)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub repository name.")
    default_branch = github(f"repos/{repository}")["default_branch"]
    query = urllib.parse.urlencode({"state": "open", "base": default_branch, "per_page": 100})
    for pr in github_pages(f"repos/{repository}/pulls?{query}"):
        head = pr["head"]
        if head.get("repo") and head["repo"]["full_name"] == repository and head["ref"].startswith(BRANCH_PREFIX):
            print(f"An upgrade is already under review: {pr['html_url']}. Preserving the branch and PR body.")
            ensure_ci(repository, pr)
            return
    base = github(f"repos/{repository}/branches/{urllib.parse.quote(default_branch, safe='')}")["commit"]["sha"]
    if command("git", "rev-parse", "HEAD", root=root) != base:
        raise ValueError("PR creation requires the current default-branch commit; retry with a fresh checkout.")
    text = (root / MANIFEST).read_text(encoding="utf-8")
    current = read_version(text)
    latest = candidate(current)
    if latest is None:
        return
    branch = f"{BRANCH_PREFIX}{latest}"
    query = urllib.parse.urlencode({"state": "all", "head": f"{repository.split('/')[0]}:{branch}", "per_page": 100})
    if github_pages(f"repos/{repository}/pulls?{query}"):
        print(f"A PR for {latest} was already closed or merged; respecting that decision. No new PR created.")
        return
    updated = re.sub(r"^emulatorVersion=.*$", f"emulatorVersion={latest}", text, flags=re.MULTILINE)
    prepare_branch(root, branch, updated)
    pr = github(f"repos/{repository}/pulls", {
        "title": f"Update AWS Emulator to {latest}",
        "head": branch,
        "base": default_branch,
        "body": pr_body(current, latest),
        "draft": True,
        "maintainer_can_modify": True,
    })
    print(f"Opened draft upgrade PR: {pr['html_url']}")
    ensure_ci(repository, pr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--create-pr", action="store_true", help="Create a draft PR using gh and a clean default-branch checkout; otherwise only check.")
    args = parser.parse_args()
    try:
        if args.create_pr:
            create_pr(args.root)
        else:
            candidate(read_version((args.root / MANIFEST).read_text(encoding="utf-8")))
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print(f"AWS Emulator update failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
