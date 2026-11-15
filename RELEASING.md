# Releasing

Both plugins are released together from a `v<version>` tag. GitHub Actions
requires successful CI for that exact commit, publishes them to the Gradle
Plugin Portal, and then publishes a GitHub Release with notes from the changelog.
Stable releases then deploy their tagged documentation to GitHub Pages. Pushes
to `main` continue to build and check docs without updating the public site.

## Make a release

Complete the [one-time setup](#one-time-setup) before your first release. For
`0.3.0`, run this from a clean `main` checkout that matches `origin/main`:

```sh
python3 scripts/release.py 0.3.0
```

The command:

1. Sets `pluginVersion` and replaces the top Unreleased changelog heading with
   the version and today's local date, preserving the release notes.
2. Commits those two files as `Release 0.3.0`, pushes `main`, and waits for that
   exact commit's [Build workflow](https://github.com/gregwhitaker/gradle-aws-emulator/actions/workflows/ci.yml)
   to pass.
3. Creates and pushes the annotated `v0.3.0` tag, starting publication of both
   plugins.

Write release notes under `## Unreleased` or `## <version> — Unreleased` before
running the command. Pass the version you want to publish; the command updates
the property and heading together. It also accepts an already-planned version
and prereleases such as `0.3.0-rc.1`.

To preview the changes and planned actions:

```sh
python3 scripts/release.py 0.3.0 --dry-run
```

The preview checks the checkout and remote refs without changing files, commits,
or tags. The release command rejects uncommitted work, conflicting tags,
`SNAPSHOT` versions, and build metadata. It never force-pushes.

Follow the [Publish to Gradle Plugin Portal run](https://github.com/gregwhitaker/gradle-aws-emulator/actions/workflows/publish.yml).
It validates the release metadata and requires a successful `main` push run of
the Build workflow for the exact tagged commit. This reuses the full CI matrix,
including CloudFormation audit, Docker integration, and publication smoke checks.
Automatic Build runs cover PRs and pushes to `main`; feature-branch and tag pushes
do not start duplicate runs. The publishing job builds the plugin
artifacts, validates their Portal metadata, and uploads both plugins without
rerunning tests. Normal local `publishPlugins` commands still run their checks.
After both uploads succeed, the separate **Create GitHub Release** job
publishes the release from the existing tag, using only that version's changelog
section for its notes. For stable versions, **Deploy stable documentation** then
builds the guide and Java API from the same tagged commit and deploys them to
GitHub Pages. It requires GitHub's latest stable release and publicly available
Plugin Portal artifacts for both plugins. Prereleases skip documentation
deployment. CI handles these checks; local verification is optional.

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
- Update the Plugin Portal installation examples in
  the [README](README.md), [companion README](spring-boot-plugin/README.md), and
  other literal installation snippets after a stable version is published.
  The guide's `{revnumber}` snippets already use `pluginVersion` from the release
  tag. Subsequent edits on `main` do not change the public website.
- Confirm **Deploy stable documentation** succeeded for a stable release. To
  redeploy it, run **Deploy docs to GitHub Pages** from `main` with the published
  tag as input. Only the latest published stable release is accepted; this does
  not upload the plugins again. See [Pages setup](docs/README.md#github-pages),
  including the environment rules required to deploy release tags.
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

Run from the repository root. Once the release command has dated the changelog,
check the metadata with Python 3:

```sh
python3 scripts/verify-release.py --ref-type tag --ref-name v0.3.0
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
  The tagged commit must also have successful `main` push CI. A failed or missing
  CI result blocks publication; rerun failed Build jobs or wait for CI, then retry
  the publishing workflow.
- **Approval pending:** wait for Portal review; do not upload the version again.
- **Only one plugin uploaded:** publish only the missing module from the same
  clean tagged checkout. The two uploads are not atomic.
- **Both plugins uploaded, but GitHub Release creation failed:** fix the reported
  issue and choose **Re-run failed jobs** for that run. This retries only the
  release job, leaving the successful Portal publication job untouched. Do not
  rerun all jobs or dispatch the whole workflow for an already uploaded version.
- **Plugins and GitHub Release published, but docs failed:** fix the reported
  deployment or environment issue, or wait for Portal approval/propagation, then
  rerun only the failed docs jobs. You can also dispatch **Deploy docs to GitHub
  Pages** from `main` with the latest stable release tag. Source corrections need
  a new stable release; redeployment always uses the existing tag's sources.

For example, if the core uploaded but the companion failed, provide
`GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` locally and run:

```sh
git switch --detach v0.3.0
./gradlew :spring-boot-plugin:publishPlugins --validate-only
./gradlew :spring-boot-plugin:publishPlugins
```

Wait for the Actions run to finish before publishing locally. Use
`:plugin:publishPlugins` if only the core is missing. Never replace an uploaded
version with different artifacts; release a new version for fixes.
If you complete a partial Portal publication manually, create the GitHub Release
from the same tag and its changelog notes afterward; the automatic release job
only runs when the Portal publication job succeeds. Then manually deploy the
documentation for that tag if it is the latest stable release.
