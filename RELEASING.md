# Releasing

Both plugins are released together from a `v<version>` tag. GitHub Actions runs
the checks, publishes them to the Gradle Plugin Portal, and then publishes a
GitHub Release with notes from the changelog.

## Make a release

Complete the [one-time setup](#one-time-setup) before your first release. For
`0.2.0`, run this from a clean `main` checkout that matches `origin/main`:

```sh
python3 scripts/release.py 0.2.0
```

The command:

1. Sets `pluginVersion` and replaces the top Unreleased changelog heading with
   the version and today's local date, preserving the release notes.
2. Commits those two files as `Release 0.2.0`, pushes `main`, and waits for that
   exact commit's [Build workflow](https://github.com/gregwhitaker/gradle-aws-emulator/actions/workflows/ci.yml)
   to pass.
3. Creates and pushes the annotated `v0.2.0` tag, starting publication of both
   plugins.

Write release notes under `## Unreleased` or `## <version> — Unreleased` before
running the command. Pass the version you want to publish; the command updates
the property and heading together. It also accepts the already-planned version
for a first release and prereleases such as `0.2.0-rc.1`.

To preview the changes and planned actions:

```sh
python3 scripts/release.py 0.2.0 --dry-run
```

The preview checks the checkout and remote refs without changing files, commits,
or tags. The release command rejects uncommitted work, conflicting tags,
`SNAPSHOT` versions, and build metadata. It never force-pushes.

Follow the [Publish to Gradle Plugin Portal run](https://github.com/gregwhitaker/gradle-aws-emulator/actions/workflows/publish.yml).
It validates the release metadata, builds the plugins and docs, runs tests
including Docker integration and publication smoke checks, and uploads both
plugins. After both uploads succeed, the separate **Create GitHub Release** job
publishes the release from the existing tag, using only that version's changelog
section for its notes. CI handles these checks; local verification is optional.

When the run succeeds, confirm the version is available on both Portal pages:
[core](https://plugins.gradle.org/plugin/io.github.gregwhitaker.aws-emulator) and
[Spring Boot companion](https://plugins.gradle.org/plugin/io.github.gregwhitaker.aws-emulator.spring-boot).
Initial publication requires [Portal approval](https://plugins.gradle.org/docs/publish-plugin#approval),
so the first upload may be pending review.

GitHub Releases are marked as prereleases when the version has a prerelease
suffix. The workflow verifies that the remote tag still identifies the built
commit. Reruns preserve an existing published release; a prepared draft is
published with its title, notes, and assets intact.

## After publishing

- Confirm the [GitHub Release](https://github.com/gregwhitaker/gradle-aws-emulator/releases)
  was published by the workflow. You can edit its notes afterward; rerunning the
  release job will preserve those edits.
- After the first public release, replace the checkout-only installation text in
  the [README](README.md), [companion README](spring-boot-plugin/README.md),
  [getting-started guide](docs/src/docs/asciidoc/getting-started.adoc), and
  [troubleshooting guide](docs/src/docs/asciidoc/troubleshooting.adoc).
  The website deploys automatically when those changes reach `main`.
- Put new development notes under a new `## Unreleased` changelog section.
  The next release command updates the version and date. Keep installation
  examples pinned to a published version and leave released tags unchanged.

## One-time setup

Create a [Plugin Portal account and API key](https://plugins.gradle.org/docs/publish-plugin)
linked to the `gregwhitaker` GitHub account. In the repository's
**Settings > Secrets and variables > Actions**, add:

- `GRADLE_PUBLISH_KEY`: the Portal API key.
- `GRADLE_PUBLISH_SECRET`: the matching secret.

Install Python 3, Git, and the [GitHub CLI](https://cli.github.com/manual/gh_auth_login),
then authenticate with `gh auth login`. Configure your Git commit identity.
Enable GitHub Actions and use an account allowed to push metadata commits to
`main` and release tags to `origin`. Existing branch protection still applies;
the command cannot push directly to a branch that requires a pull request.
GitHub Release creation uses the workflow's built-in `GITHUB_TOKEN`, with
`contents: write` granted only to the release job; no extra secret is needed.
For website hosting, follow [GitHub Pages setup](docs/README.md#github-pages).
Java, Docker, and Portal publishing credentials are provided by the workflow.

## Optional local checks

Run from the repository root. The metadata check requires Python 3:

```sh
python3 scripts/verify-release.py --ref-type tag --ref-name v0.2.0
```

To check the build and publication before pushing, use Java 17+ and Bash:

```sh
./gradlew build :plugin:publishToMavenLocal :spring-boot-plugin:publishToMavenLocal "-Dmaven.repo.local=$PWD/build/publication-check"
bash scripts/smoke-test-publication.sh "$PWD/build/publication-check"
```

These commands write to a local repository and require no Portal credentials.
For real-emulator tests, run `./gradlew dockerTest` with Docker running.

## If publication fails

If the command stops after creating its commit, rerun the same command from the
unchanged checkout to resume. It preserves the original release date and reuses
the commit and any matching local tag. A tag already pushed is reported without
starting another publication. If committing fails, review the retained metadata
edits before retrying.

CI failure or timeout leaves the tag unpushed. Fix or rerun the failed CI checks,
then resume the command. The default wait is one hour; use `--timeout 7200` to
wait up to two hours. Code fixes require a new release version.

Check the failed Actions step and both plugins' Portal status before retrying.

- **Nothing uploaded:** fix credentials or a transient failure and rerun the
  failed workflow. Source or release-metadata changes need a new version and tag.
  Manual dispatch must target the matching version tag; `main` is rejected.
- **Approval pending:** wait for Portal review; do not upload the version again.
- **Only one plugin uploaded:** publish only the missing module from the same
  clean tagged checkout. The two uploads are not atomic.
- **Both plugins uploaded, but GitHub Release creation failed:** fix the reported
  issue and choose **Re-run failed jobs** for that run. This retries only the
  release job, leaving the successful Portal publication job untouched. Do not
  rerun all jobs or dispatch the whole workflow for an already uploaded version.

For example, if the core uploaded but the companion failed, provide
`GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` locally and run:

```sh
git switch --detach v0.2.0
./gradlew :spring-boot-plugin:publishPlugins --validate-only
./gradlew :spring-boot-plugin:publishPlugins
```

Wait for the Actions run to finish before publishing locally. Use
`:plugin:publishPlugins` if only the core is missing. Never replace an uploaded
version with different artifacts; release a new version for fixes.
If you complete a partial Portal publication manually, create the GitHub Release
from the same tag and its changelog notes afterward; the automatic release job
only runs when the Portal publication job succeeds.
