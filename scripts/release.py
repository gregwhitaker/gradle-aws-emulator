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

"""Prepare a release, push its metadata commit, and push its tag after CI passes."""

import argparse
import datetime
import difflib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import time


# Import the same metadata rules used by the publishing workflow without creating
# an untracked __pycache__ directory in an otherwise clean release checkout.
spec = importlib.util.spec_from_file_location("verify_release", Path(__file__).with_name("verify-release.py"))
guard = importlib.util.module_from_spec(spec)
exec(compile(spec.loader.get_source(spec.name), str(spec.origin), "exec"), guard.__dict__)

FILES = ("gradle.properties", "CHANGELOG.md")
HEADINGS = re.compile(r"^## ([^\r\n]+)(\r?)$", re.MULTILINE)


def command(*args, root, raw=False):
    result = subprocess.run(args, cwd=root, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"{args[0]} {args[1]} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout if raw else result.stdout.strip()


def repository_name(url):
    path = re.sub(r"^(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)", "", url)
    if path == url:
        raise ValueError("origin must point to a GitHub repository using HTTPS or SSH.")
    if path.endswith(".git"):
        path = path[:-4]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", path):
        raise ValueError("origin has an unsupported GitHub repository URL.")
    return path


def prepare_metadata(properties, changelog, version, date):
    """Return updated text and whether this version was already prepared."""
    current = guard.read_version(properties)
    guard.read_version(f"pluginVersion={version}\n")
    headings = list(HEADINGS.finditer(changelog))
    if not headings:
        raise ValueError("CHANGELOG.md needs a top Unreleased section with release notes.")
    top = headings[0]
    if re.fullmatch(rf"{re.escape(version)} — \d{{4}}-\d{{2}}-\d{{2}}", top[1]):
        guard.verify_release(properties, changelog, "tag", f"v{version}")
        return properties, changelog, True
    if top[1] != "Unreleased" and not re.fullmatch(r"\S+ — Unreleased", top[1]):
        raise ValueError("Add a top '## Unreleased' section before preparing another release.")
    if top[1] != "Unreleased":
        guard.read_version(f"pluginVersion={top[1].split(' — ')[0]}\n")
    if tuple(map(int, version.split("-")[0].split("."))) < tuple(map(int, current.split("-")[0].split("."))):
        raise ValueError(f"Release version {version} is older than pluginVersion {current}.")
    if any(heading[1].startswith(f"{version} — ") and not heading[1].endswith(" — Unreleased") for heading in headings[1:]):
        raise ValueError(f"CHANGELOG.md already contains a release for {version}.")
    section_end = headings[1].start() if len(headings) > 1 else len(changelog)
    if not changelog[top.end():section_end].strip():
        raise ValueError("The Unreleased section needs release notes before publishing.")
    updated_properties = re.sub(r"^pluginVersion=[^\r\n]*", lambda _: f"pluginVersion={version}", properties, flags=re.MULTILINE)
    updated_changelog = changelog[:top.start()] + f"## {version} — {date}{top[2]}" + changelog[top.end():]
    guard.verify_release(updated_properties, updated_changelog, "tag", f"v{version}")
    return updated_properties, updated_changelog, False


def clean_head(root):
    if command("git", "branch", "--show-current", root=root) != "main":
        raise ValueError("Run the release command from main.")
    if command("git", "status", "--porcelain", "--untracked-files=all", root=root):
        raise ValueError("Release requires a clean checkout; commit or remove outstanding changes first.")
    return command("git", "rev-parse", "HEAD", root=root)


def remote_refs(root, tag):
    output = command("git", "ls-remote", "origin", "refs/heads/main", f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}", root=root)
    return {ref: sha for sha, ref in (line.split() for line in output.splitlines())}


def prepared_parent(root, version, properties, changelog):
    """Resume only a metadata-only commit produced by this command."""
    parents = command("git", "rev-list", "--parents", "-n", "1", "HEAD", root=root).split()
    changed = set(command("git", "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD", root=root).splitlines())
    if (len(parents) != 2 or not changed or not changed.issubset(FILES)
            or command("git", "log", "-1", "--format=%s", root=root) != f"Release {version}"):
        raise ValueError("The dated release must be this command's metadata-only release commit to resume.")
    before = [command("git", "show", f"{parents[1]}:{name}", root=root, raw=True) for name in FILES]
    date = HEADINGS.search(changelog)[1].rsplit(" — ", 1)[1]
    expected_properties, expected_changelog, already_prepared = prepare_metadata(*before, version, date)
    # Git's text output normalizes CRLF, as may checkout line-ending settings.
    if (already_prepared or expected_properties.replace("\r\n", "\n") != properties.replace("\r\n", "\n")
            or expected_changelog.replace("\r\n", "\n") != changelog.replace("\r\n", "\n")):
        raise ValueError("The release commit contains changes beyond the version and changelog heading.")
    return parents[1]


def wait_for_ci(root, repository, commit, timeout):
    deadline = time.monotonic() + timeout
    last_status = None
    while True:
        runs = json.loads(command(
            "gh", "run", "list", "--repo", repository, "--workflow", "ci.yml", "--branch", "main",
            "--event", "push", "--commit", commit, "--limit", "1",
            "--json", "headSha,status,conclusion,url", root=root))
        run = next((item for item in runs if item["headSha"] == commit), None)
        status = f"CI {run['status']}: {run['url']}" if run else "Waiting for the Build workflow to start..."
        if status != last_status:
            print(status, flush=True)
            last_status = status
        if run and run["status"] == "completed":
            if run["conclusion"] != "success":
                raise ValueError(f"CI finished with {run['conclusion']}: {run['url']}. No release tag was pushed.")
            return
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError("Timed out waiting for CI. Rerun the same release command to resume; no tag was pushed.")
        time.sleep(min(10, remaining))


def release(root, version, *, dry_run=False, timeout=3600, date=None):
    guard.read_version(f"pluginVersion={version}\n")
    head = clean_head(root)
    for name in FILES:
        if (root / name).is_symlink():
            raise ValueError(f"{name} must be a regular tracked file.")
    command("git", "ls-files", "--error-unmatch", "--", *FILES, root=root)
    original = [(root / name).read_bytes().decode("utf-8") for name in FILES]
    properties, changelog, resume = prepare_metadata(*original, version, date or datetime.date.today().isoformat())
    parent = prepared_parent(root, version, *original) if resume else head
    repository = repository_name(command("git", "remote", "get-url", "origin", root=root))
    if repository_name(command("git", "remote", "get-url", "--push", "origin", root=root)) != repository:
        raise ValueError("origin must fetch from and push to the same GitHub repository.")
    tag = f"v{version}"
    refs = remote_refs(root, tag)
    remote_tag = refs.get(f"refs/tags/{tag}^{{}}", refs.get(f"refs/tags/{tag}"))
    if remote_tag:
        if resume and remote_tag == head:
            print(f"{tag} is already pushed. Follow https://github.com/{repository}/actions/workflows/publish.yml")
            return
        raise ValueError(f"Remote tag {tag} already exists; choose a new release version.")
    local_tag = command("git", "tag", "--list", tag, root=root)
    if local_tag and (not resume or command("git", "rev-parse", f"refs/tags/{tag}^{{commit}}", root=root) != head):
        raise ValueError(f"Local tag {tag} already exists for another release; it was not changed.")
    remote_main = refs.get("refs/heads/main")
    if remote_main != head and not (resume and remote_main == parent):
        raise ValueError("main must match origin/main, or be one unpushed release commit ahead. Update your checkout first.")
    if dry_run:
        for name, before, after in zip(FILES, original, (properties, changelog)):
            print("".join(difflib.unified_diff(before.splitlines(True), after.splitlines(True), fromfile=name, tofile=name)), end="")
        print(f"Would {'resume' if resume else 'commit'} Release {version}, push main, wait for CI, then push {tag} to {repository}.")
        return

    command("gh", "auth", "status", "--active", "--hostname", "github.com", root=root)
    if clean_head(root) != head:
        raise ValueError("The checkout changed during release preparation; retry from a clean main checkout.")
    if not resume:
        for name, text in zip(FILES, (properties, changelog)):
            (root / name).write_bytes(text.encode("utf-8"))
        command("git", "commit", "--only", "-m", f"Release {version}", "--", *FILES, root=root)
        head = clean_head(root)
        prepared_parent(root, version, *[(root / name).read_bytes().decode("utf-8") for name in FILES])
        print(f"Created release commit {head}.", flush=True)
    if remote_main != head:
        command("git", "push", "--no-follow-tags", "origin", f"{head}:refs/heads/main", root=root)
        print("Pushed release commit to main.", flush=True)
    wait_for_ci(root, repository, head, timeout)
    if clean_head(root) != head:
        raise ValueError("The checkout changed while CI ran; no release tag was pushed.")
    if not local_tag:
        command("git", "tag", "-a", tag, "-m", f"Release {version}", head, root=root)
    if command("git", "rev-parse", f"refs/tags/{tag}^{{commit}}", root=root) != head:
        raise ValueError(f"Local tag {tag} changed while CI ran; it was not pushed.")
    command("git", "push", "--no-follow-tags", "origin", f"refs/tags/{tag}", root=root)
    print(f"Pushed {tag}; publishing starts at https://github.com/{repository}/actions/workflows/publish.yml")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", help="Release version, e.g. 0.2.0 or 0.2.0-rc.1.")
    parser.add_argument("--dry-run", action="store_true", help="Show changes and steps without writing files, committing, or pushing.")
    parser.add_argument("--timeout", type=int, default=3600, help="Maximum seconds to wait for CI (default: 3600).")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be greater than zero")
    try:
        release(args.root, args.version, dry_run=args.dry_run, timeout=args.timeout)
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Release stopped: {error}", file=sys.stderr)
        print("Existing commits and tags were preserved. See RELEASING.md for resuming.", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nRelease interrupted. Rerun the same command from the unchanged checkout to resume.", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
