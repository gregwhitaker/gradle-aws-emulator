# Releasing

Both plugins are released together from a `v<version>` tag. GitHub Actions runs
the checks and publishes them to the Gradle Plugin Portal.

## Make a release

Complete the [one-time setup](#one-time-setup) before your first release. For
`0.2.0` (substitute your version and release date):

1. Set `pluginVersion=0.2.0` in [gradle.properties](gradle.properties) and change
   the top release heading in [CHANGELOG.md](CHANGELOG.md) to
   `## 0.2.0 — 2026-11-14`.
2. Commit and merge those changes to `main`. Wait for the
   [Build workflow](https://github.com/gregwhitaker/gradle-aws-emulator/actions/workflows/ci.yml)
   to pass for that commit.
3. From a clean checkout of that passing commit, tag and push:

   ```sh
   git tag -a v0.2.0 -m "Release 0.2.0"
   git push origin refs/tags/v0.2.0
   ```

Follow the [Publish to Gradle Plugin Portal run](https://github.com/gregwhitaker/gradle-aws-emulator/actions/workflows/publish.yml).
It validates the release metadata, builds the plugins and docs, runs tests
including Docker integration and publication smoke checks, and uploads both
plugins. CI handles these checks; local verification is optional.

When the run succeeds, confirm the version is available on both Portal pages:
[core](https://plugins.gradle.org/plugin/io.github.gregwhitaker.aws-emulator) and
[Spring Boot companion](https://plugins.gradle.org/plugin/io.github.gregwhitaker.aws-emulator.spring-boot).
Initial publication requires [Portal approval](https://plugins.gradle.org/docs/publish-plugin#approval),
so the first upload may be pending review.

The property, top release heading, and tag must agree. Prereleases such as
`0.2.0-rc.1` are supported; `SNAPSHOT` and build metadata are rejected. Keep the
dated release section first until publication completes.

## After publishing

- If you want GitHub release notes, create a release from the existing tag using
  the changelog entry. Mark prereleases accordingly.
- After the first public release, replace the checkout-only installation text in
  the [README](README.md), [companion README](spring-boot-plugin/README.md),
  [getting-started guide](docs/src/docs/asciidoc/getting-started.adoc), and
  [troubleshooting guide](docs/src/docs/asciidoc/troubleshooting.adoc).
  The website deploys automatically when those changes reach `main`.
- When starting the next development cycle, update `pluginVersion` and add a new
  `## <next-version> — Unreleased` changelog section. Keep installation examples
  pinned to a published version and leave released tags unchanged.

## One-time setup

Create a [Plugin Portal account and API key](https://plugins.gradle.org/docs/publish-plugin)
linked to the `gregwhitaker` GitHub account. In the repository's
**Settings > Secrets and variables > Actions**, add:

- `GRADLE_PUBLISH_KEY`: the Portal API key.
- `GRADLE_PUBLISH_SECRET`: the matching secret.

Enable GitHub Actions and ensure you can push release tags to the repository.
For website hosting, follow [GitHub Pages setup](docs/README.md#github-pages).
Java, Docker, and publishing credentials are provided by the workflow; you only
need Git locally for the normal release path.

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

Check the failed Actions step and both plugins' Portal status before retrying.

- **Nothing uploaded:** fix credentials or a transient failure and rerun the
  failed workflow. Source or release-metadata changes need a new version and tag.
  Manual dispatch must target the matching version tag; `main` is rejected.
- **Approval pending:** wait for Portal review; do not upload the version again.
- **Only one plugin uploaded:** publish only the missing module from the same
  clean tagged checkout. The two uploads are not atomic.

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
