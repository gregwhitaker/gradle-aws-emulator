# Contributing

Contributions to the AWS Emulator Gradle plugins are welcome, including bug fixes,
new features, documentation, and examples. Read the [README](README.md) for public
behavior and usage before making changes.

## Report a bug or propose a feature

Search the repository's GitHub issues and pull requests for related work first.
For a bug report, include:

- A minimal build or example that reproduces the problem, with the command to run.
- Expected behavior and actual behavior, including relevant error output.
- Plugin, Java, and Gradle versions, plus the operating system and architecture.
- Docker and emulator versions when the problem involves a running emulator.

Remove credentials and other sensitive information from logs and reproductions.
For a substantial feature or API change, open an issue describing the use case and
proposed behavior so the approach can be discussed before implementation.

## Set up a development checkout

1. Fork and clone the repository, then create a branch for your change.
2. Install JDK 17 or newer and ensure `JAVA_HOME` points to that installation.
3. From the repository root, run:

   ```sh
   ./gradlew build
   ```

Use the checked-in Gradle wrapper (`gradlew.bat` on Windows). It pins Gradle
8.14.3; consumers of the plugins require Gradle 8.14 or newer. Production code
targets Java 17, and build scripts use the Gradle Kotlin DSL.

The standard build runs both plugins' unit and functional checks, plugin
validation, and documentation generation, verification, and packaging. Docker is
only required for real-emulator integration tests and examples. Those require the
`docker` CLI and a running local engine using Linux containers; the first run
downloads or builds the emulator image. No AWS account or AWS CLI is required.

## Find the right place for a change

| Location | Purpose |
| --- | --- |
| `plugin/` | Core plugin, lifecycle management, resource tasks, and consumer integration. |
| `spring-boot-plugin/` | Optional Spring Boot companion plugin. |
| `docs/` | AsciiDoc user guide, site assets, Java API reference assembly, and link checker. |
| `examples/` | Independent composite builds that consume this checkout. |
| `smoke-tests/` | Independent consumers used to verify published plugin artifacts. |

Run examples with `./gradlew -p examples/<example> ...`; they are not root
subprojects. The shared plugin and documentation version is `pluginVersion` in
`gradle.properties`. See the [CI workflow](.github/workflows/ci.yml) for the Java,
Gradle, operating system, and architecture combinations checked in CI.

## Implementation conventions

- Follow the existing Java formatting and feature-based packages under
  `io.github.gregwhitaker.awsemulator`. Keep each AWS service's module, task types,
  and provisioning helpers together under `aws/<service>/`.
- Keep task registration in modules, runtime ownership in `AwsEmulatorService`,
  Docker and process details under `internal/`, and Spring Boot integration in
  the companion plugin. Packages named `internal` are implementation details.
- Register and configure tasks lazily. Use Gradle properties and providers with
  appropriate task input/output annotations. Preserve configuration-cache
  compatibility and avoid capturing `Project` in execution-time actions.
- Applying a plugin must not start Docker or opt existing tasks into the emulator.
  Resolve connections, credentials, and generated TLS files during execution.
- Preserve lifecycle behavior: managed instances are shared by participating
  tasks and cleaned up after use, including failures; development instances
  persist until explicitly stopped. Custom resource tasks default to `MANAGED`,
  while module convenience tasks target `DEVELOPMENT`.
- Tasks operating on live emulator state must always execute, with up-to-date
  skipping and build-cache reuse disabled. Configuration-cache reuse is supported.
- Use `AwsClientFactory` for resource-task SDK clients and close them after use.
  Preserve explicit local endpoints, emulator credentials, and certificate and
  hostname validation. Scope connection settings to consumers and clients; do not
  change the Gradle daemon's credentials or global TLS settings.

## Test your change

Run checks appropriate to the change from the repository root:

| Command | Coverage |
| --- | --- |
| `./gradlew build` | Complete build and verification without Docker. |
| `./gradlew :plugin:test` | Core unit tests. |
| `./gradlew :plugin:functionalTest :spring-boot-plugin:functionalTest` | Gradle TestKit checks without Docker. |
| `./gradlew dockerTest` | Both plugins' integration suites against a real emulator. |
| `./gradlew :docs:check` | Generated site links, anchors, assets, and theme controls. |

Use `--tests '<fully.qualified.TestClass>'` with a specific test task to focus on
one class. For runtime, SDK, or lifecycle changes, run the relevant module's
`integrationTest` task when Docker is available. Some integration tests
intentionally fail nested fixture builds to verify cleanup; the enclosing tests
should pass. Note any checks you could not run in your pull request.

Maintainers can start a fresh CI validation from GitHub's **Actions → Build →
Run workflow**, selecting the branch to check. This runs the same build,
publication smoke, release-tool, and Docker checks as push and pull-request
validation; it does not publish plugins. The Build workflow must be enabled for
manual runs, and GitHub only offers manual dispatch once the workflow containing
`workflow_dispatch` is on the default branch.

Use JUnit 5 and mirror the production packages. Put unit tests in `src/test/`,
Docker-free TestKit tests in `src/functional/`, and real-emulator tests in
`src/integration/` within the relevant module. Reuse `GradleBuilds` from
`plugin/src/testFixtures/` for TestKit setup; it enables configuration-cache
checks with problems treated as failures. To test another Gradle distribution,
use `-PtestGradleVersion=<version>` or
`-PtestGradleInstallation=/path/to/gradle`.

To exercise a complete consumer with Docker, run this command twice and check
that the second run reuses the configuration cache:

```sh
./gradlew -p examples/basic integrationTest --configuration-cache
```

## Update documentation and examples

When public task names, properties, defaults, or behavior change, update the
README, the relevant AsciiDoc guide, and runnable examples. Document public Java
APIs with Javadoc.

Edit guide sources in `docs/src/docs/asciidoc/`, browser assets in
`docs/src/site/`, and API templates in `docs/src/templates/`. Add new guide pages
to the shared navigation as described in [docs/README.md](docs/README.md).
Run `./gradlew :docs:check` and open `docs/build/site/index.html` to review changes
to the generated site.

## Submit a pull request

- Keep the change focused and explain the problem and resulting behavior.
- Link any related issue and include reproduction steps when useful.
- Add or update tests for behavior changes, including failure cleanup and
  configuration-cache reuse where relevant.
- Describe the checks you ran, their results, and any checks you could not run.
- Include documentation and example updates for public changes. Keep generated
  `build/`, `.gradle/`, and `.aws-emulator/` contents out of commits.

## Publish to the Gradle Plugin Portal

The release version comes from `pluginVersion` in the root `gradle.properties`. The core artifact is `io.github.gregwhitaker:gradle-aws-emulator`; the companion is `io.github.gregwhitaker:gradle-aws-emulator-spring-boot` and depends on the core. Both use the shared version. Publishing includes the implementation JAR, sources, Javadoc, dependency metadata, and the plugin marker; test fixtures and the documentation subproject are not published. `publishPlugins` runs the plugin's unit tests, functional tests, and plugin validation before publishing.

### Verify the publication locally

Run these commands from the repository root without publishing credentials or uploading artifacts:

```sh
./gradlew build :plugin:publishToMavenLocal :spring-boot-plugin:publishToMavenLocal -Dmaven.repo.local="$PWD/build/publication-check"
bash scripts/smoke-test-publication.sh "$PWD/build/publication-check"
./gradlew dockerTest
```

The first command writes the implementation and marker publications to `build/publication-check/` without changing your usual Maven local repository. The smoke consumers restrict emulator artifacts to that repository, verify public task types and transitive dependencies, and package a minimal application with Spring Boot without starting Docker. The companion consumer applies only the companion ID, checking its dependency on the core artifact. Each consumer runs twice, and the script fails unless the second run reuses the configuration cache. The script requires Bash (Git Bash on Windows); set `GRADLE` to a Gradle executable to check another installed distribution. Portal validation with `--validate-only` also requires publishing credentials and contacts the Portal, but does not upload the release.

### Publish with GitHub Actions

Create a [Plugin Portal account and API key](https://plugins.gradle.org/docs/publish-plugin), linked to the `gregwhitaker` GitHub account so the Portal can verify the plugin namespace. Add the key and secret to this repository's GitHub Actions secrets as `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`.

To release through GitHub Actions:

1. Set `pluginVersion` to the release version. Use `MAJOR.MINOR.PATCH` or a SemVer prerelease such as `0.2.0-rc.1`, without `SNAPSHOT` or build metadata. Finalize the top changelog section with that exact version and a real release date, for example `## 0.2.0 — 2026-11-12`, replacing `Unreleased` only when releasing. Commit the release changes.
2. Push that commit and a matching version tag, for example `v0.2.0` for `pluginVersion=0.2.0`.
3. The [publish workflow](.github/workflows/publish.yml) checks the tag and dated changelog, builds both plugins and docs, runs unit/functional/Docker integration tests, publishes to an isolated local repository, and checks both smoke consumers including configuration-cache reuse before validating and uploading both plugins. It can also be dispatched manually against a matching version tag.

Before tagging, run the metadata guard locally (no credentials or network needed), substituting your version:

```sh
python3 -m unittest discover -s scripts/tests -v
python3 scripts/verify-release.py --ref-type tag --ref-name v0.2.0
```

The guard intentionally rejects the current `Unreleased` changelog. Passing it verifies metadata, not the build or Portal approval. Review successful CI, including both Docker architectures, for the intended release commit before pushing the tag.

For example, from the intended release commit:

```sh
git tag v0.2.0
git push origin v0.2.0
```

### Publish from your machine

To publish locally, provide `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` in your environment, or put `gradle.publish.key` and `gradle.publish.secret` in your user-level `~/.gradle/gradle.properties`. Keep credentials out of this repository. After running the validation commands above:

```sh
./gradlew :plugin:publishPlugins --validate-only :spring-boot-plugin:publishPlugins --validate-only
./gradlew :plugin:publishPlugins :spring-boot-plugin:publishPlugins
```

The two Portal publications are not atomic. If the core uploads but the companion fails, inspect both versions on the Portal before retrying. Do not blindly rerun the full publish workflow against an already published core version; validate and publish only the missing companion from the same release commit after resolving the failure. Never replace an uploaded version with different artifacts.

The first publication requires [Portal approval](https://plugins.gradle.org/docs/publish-plugin#approval). Published versions are immutable; use a new version for subsequent releases. After approval, update the publication-status text in the [README](README.md) and the user guide. Applications can then apply the released plugin without a composite build:

```kotlin
plugins {
    id("io.github.gregwhitaker.aws-emulator") version "0.2.0"
}
```

This project is licensed under the [Apache License 2.0](LICENSE).
