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

The standard build runs both plugins' unit and functional checks, code quality
checks, plugin validation, and documentation generation, verification, and
packaging. Docker is only required for real-emulator integration tests and
examples. Those require the `docker` CLI and a running local engine using Linux
containers; the first run
downloads or builds the emulator image. No AWS account or AWS CLI is required.

The containerized-runtime integration test also builds a Gradle runner image and
starts a temporary Docker API relay on a private Docker network. The relay uses
the engine's `/var/run/docker.sock`; it publishes no host port. The runner has no
workspace bind mount. This verifies default and named shared runtimes through a
separate TCP endpoint, including configuration-cache reuse and failure cleanup.

## Find the right place for a change

| Location | Purpose |
| --- | --- |
| `build-logic/` | Internal build convention plugins for Java, code quality, license headers, publishing, JaCoCo coverage, and test logging. |
| `plugin/` | Core plugin, lifecycle management, resource tasks, and consumer integration. |
| `spring-boot-plugin/` | Optional Spring Boot companion plugin. |
| `docs/` | AsciiDoc user guide, site assets, Java API reference assembly, and link checker. |
| `examples/` | Independent composite builds that consume this checkout. |
| `smoke-tests/` | Independent consumers used to verify published plugin artifacts. |

Run examples with `./gradlew -p examples/<example> ...`; they are not root
subprojects. The shared plugin and documentation version is `pluginVersion` in
`gradle.properties`. See the [CI workflow](.github/workflows/ci.yml) for the Java,
Gradle, operating system, and architecture combinations checked in CI.

## AWS Emulator upgrades

The tested runtime default lives in [`gradle/emulator.properties`](gradle/emulator.properties).
The core plugin bundles this manifest in its JAR; its default runtime, external
emulator test fixtures, and the generated guide read the same version. Consumers
can still set `awsEmulator.emulatorVersion` explicitly. CloudFormation's audited
version and catalogue are maintained separately, so changing the default cannot
automatically claim support for a new release.
Completed source reviews are recorded under [`docs/emulator-audits/`](docs/emulator-audits/1.0.367.md).

The [AWS Emulator upgrade workflow](.github/workflows/emulator-update.yml) checks
daily at **11:23 UTC** and supports manual dispatch from the default branch. It
reads AWS's [published-version index](https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/versions.json),
checks for a stable GitHub release and both AMD64 and ARM64 RPM packages, then
opens a **draft** PR changing only the shared manifest. It explicitly dispatches
the Build workflow on the upgrade branch using `GITHUB_TOKEN`; no PAT is needed.
Token-created pushes do not themselves start push workflows, and token-created
PR workflows can require approval, so the explicit dispatch is intentional.

Only one upgrade PR is active at a time. An existing PR's branch, checklist, and
review commits are preserved, even if a newer release appears. If dispatch failed
after creation, the next daily run retries it. Existing checks are not repeatedly
rerun for the same commit; rerun failed checks in Actions or push a correction.
Closing an upgrade PR declines that version; reopen it to reconsider. A later
version can create a new PR once no upgrade PR is open. The updater never
approves, merges, or enables auto-merge.

Check for updates locally without changing files or GitHub:

```sh
python3 scripts/update-emulator.py
```

The index currently identifies packages as `MAJOR.MINOR.PATCH.REVISION`. The
updater requires revision `1`, matching the RPM filename built by
`DockerImageBuilder`. A future revision or feed-format change requires a tooling
review; it is not silently treated as the currently supported package.

### Required CloudFormation audit

Every candidate initially fails **CloudFormation audit** until a maintainer
completes these steps:

1. Review the new release notes and compare upstream CloudFormation
   `src/state/provisioner.rs` and `src/state/provisioning.rs`, following delegated
   handlers as needed. Distinguish actual service provisioning from handlers
   that only record success. Preserve the validator's restrictions on nested
   stacks, transforms, and custom providers.
2. Create `plugin/src/main/resources/io/github/gregwhitaker/awsemulator/aws/cloudformation/provisioned-types-<version>.txt`
   from the reviewed handlers. Record the exact upstream commit and source URLs
   in its header. Copying the old list and changing its version is not an audit.
3. Update `CloudFormationTemplate.EMULATOR_VERSION` after reviewing the catalogue,
   and update affected unit, functional, and real-service provisioning tests.
   `CloudFormationAuditTest` requires the default and audited versions to agree
   and checks the catalogue's source references. These checks validate the audit
   record; the approving maintainer must assess the audit's correctness.
4. Review version-specific claims in the README, guides, and examples, including
   CloudFormation, SES, and Cognito. Update their evidence and compatibility
   notes. Keep historical changelog entries tied to their original versions.
5. Require **Emulator compatibility** to pass. It aggregates every supported
   Java/Gradle build, publication smoke checks, updater tests, the CloudFormation
   audit, both plugins' Docker suites on AMD64/ARM64, and two basic-example runs
   that verify configuration-cache reuse. Review failures before changing test
   expectations; upstream fidelity fixes can change behavior in patch releases.

For a focused audit check, run:

```sh
./gradlew :plugin:test --tests 'io.github.gregwhitaker.awsemulator.aws.cloudformation.CloudFormationAuditTest'
```

Run `./gradlew build dockerTest` and the basic example twice for local
compatibility verification. The full CI matrix runs on the candidate branch.
After the audit, documentation, and checks are complete, mark the PR ready and
obtain an approving maintainer review before merging. Publish a plugin release
to deliver the new tested default to consumers.

### GitHub setup

Enable both **AWS Emulator upgrade** and **Build** under Actions. The upgrade
workflow must be on the default branch for scheduled runs. Enable
**Settings → Actions → General → Allow GitHub Actions to create and approve pull
requests**; the updater uses only PR creation, not approval. Its job requests
`contents: write`, `pull-requests: write`, and `actions: write` for branch creation,
draft PR creation, and explicit CI dispatch.

Protect the default branch with at least **one approving review**, **dismiss
stale approvals when new commits are pushed**, and required status checks
**CloudFormation audit** and **Emulator compatibility**. Require the branch to be
up to date before merging. Drafts and the unchecked checklist guide the process;
branch protection enforces review and passing checks in GitHub. These settings
apply to all PRs targeting that branch. The workflow does not change repository
settings or bypass review.

## Implementation conventions

Both plugin modules apply `build.java-conventions` from the `java-conventions`
subproject of the included `build-logic` build. Its configuration lives in
`build-logic/java-conventions/src/main/kotlin/build.java-conventions.gradle.kts`.
It provides Maven Central, Java 17 compatibility with UTF-8 compilation, source
and Javadoc JARs, packaged license notices, shared Javadoc settings, JUnit 5,
and the functional and integration test suites. Functional tests participate in
`check`; integration tests run when explicitly requested. Compilation and tests
use the JDK selected by the developer or CI, with `--release 17` enforcing the
compiled code's Java compatibility.

The Java convention also applies `build.spotbugs` and `build.test-logger`. Both
modules also apply `build.jacoco` and `build.publishing`. Module build scripts
supply production dependencies, Gradle plugin declarations, artifact IDs and POM
names, TestKit setup, and example-specific test inputs.

Tests that reuse runnable examples declare them with `useExample(...)` or
`useFrameworkExamples(...)`. These helpers live in
`build-logic/java-conventions/src/main/kotlin/buildlogic/testing/ExampleFixtures.kt`
and configure both the fixture system properties and relative-path file inputs.
Use `useExample("multi-project", multiProject = true)` for nested projects.
Hyphenated example names become camel-case property names, such as
`spring-cloud-aws` becoming `springCloudAws.example`.

- Follow the existing Java formatting and feature-based packages under
  `io.github.gregwhitaker.awsemulator`. Keep each AWS service's module, task types,
  and provisioning helpers together under `aws/<service>/`.
- Keep task registration in modules, runtime ownership in `AwsEmulatorService`,
  Docker and process details under `internal/`, and Spring Boot integration in
  the companion plugin. Packages named `internal` are implementation details.
- Register and configure tasks lazily. Use Gradle properties and providers with
  appropriate task input/output annotations. Preserve configuration-cache
  compatibility and avoid capturing `Project` in execution-time actions.
- Preserve Isolated Projects support. Exchange shared runtime metadata through
  project dependencies and attach foreign setup by task path. Do not read another
  project's extensions/tasks or resolve task dependency graphs directly. The
  `LocalTaskDependencies` helper contains a narrow Gradle-internal bridge for
  provider producer metadata; keep its output-provider and runtime-selection
  regression tests passing across the supported Gradle matrix.
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
| `./gradlew qualityCheck` | Formatting, license headers, and production Java analysis without running tests or Docker. |
| `./gradlew spotlessCheck` | Repository source whitespace and final newlines. |
| `./gradlew spotbugsMain` | Static bug analysis of both plugins' production code. |
| `./gradlew licenseCheck` | Apache 2.0 headers across the repository. |
| `./gradlew jacocoTestReport` | Combined and per-plugin unit/functional coverage in HTML and XML. |
| `./gradlew jacocoIntegrationTestReport` | Combined and per-plugin integration coverage; requires Docker. |

Functional tests include Isolated Projects checks in both DSLs, shared and named
runtime selection, parallel configuration, owner-change invalidation, and cache
reuse. Named/shared runtime and Spring Boot integration fixtures also enable
isolation. Run the focused isolation fixture against another Gradle version with:

```sh
./gradlew :plugin:functionalTest --tests '*IsolatedProjectsFunctionalTest' -PtestGradleVersion=9.6.1
```

Both plugin modules receive `build.test-logger` through `build.java-conventions`.
The logger comes from the `test-logger` subproject of the included `build-logic`
build. Its configuration lives in
`build-logic/test-logger/src/main/kotlin/build.test-logger.gradle.kts` and uses
[Test Logger](https://github.com/radarsh/gradle-test-logger-plugin) with the Mocha
theme and simple class names for per-test results, failure details, slow-test
timings, and suite summaries across unit, functional, and integration tests. Add
`--console=plain` for output without colors or Unicode symbols, or
`-Dtestlogger.theme=standard` to select another theme.

The root build applies `build.license` from the `license` subproject of the
included `build-logic` build. Its configuration lives in
`build-logic/license/src/main/kotlin/build.license.gradle.kts` and uses
[Licenser](https://github.com/MinecraftForge/licenser) to apply the notice in
`build-logic/license/src/main/resources/build.license/license-header.txt`, bundled with
the plugin. Run `./gradlew licenseFormat` after
adding source files, then `./gradlew licenseCheck` to verify them. The root `check`
and `build` tasks include this verification; both license tasks support the
configuration cache.

Headers cover Java and Kotlin sources, Gradle scripts, Python and shell scripts,
workflow/configuration files, and documentation site code, including the
independent examples and publication smoke consumers. Generated output, Gradle
wrapper files, fixture payloads, prose, and files without comment syntax are
excluded. Keep the copyright year in the shared template fixed unless deliberately
updating the project's notice.

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

### Code quality checks

The root `check` and `build` tasks include `qualityCheck`, so the existing CI build
enforces these checks. Run `./gradlew qualityCheck --configuration-cache` for a
focused check without tests or Docker; run it again to verify cache reuse.

The root applies [Spotless](https://github.com/diffplug/spotless) through
`build-logic/spotless/src/main/kotlin/build.spotless.gradle.kts`. It checks Java,
Kotlin, Gradle scripts, scripts/configuration files, and documentation site code,
including the independent examples and smoke consumers. It removes trailing
whitespace, adds final newlines, and converts leading tabs to four spaces in Java
and Kotlin sources. Existing line wrapping, import order, and brace style are
preserved. Prose, fixture payloads, wrapper files, and generated directories are
excluded. License headers remain the responsibility of `build.license`.

Run `./gradlew spotlessApply` to fix formatting and `./gradlew spotlessCheck` to
verify it. The check tasks never rewrite source files.

Both plugin modules receive [SpotBugs](https://github.com/spotbugs/spotbugs-gradle-plugin)
through `build.spotbugs`, configured in
`build-logic/spotbugs/src/main/kotlin/build.spotbugs.gradle.kts`. Analysis uses
maximum effort and reports medium- and high-confidence findings as build failures.
`check` analyzes production classes only: test code and TestKit fixtures deliberately
exercise invalid inputs and failure paths. Other source-set tasks, such as
`:plugin:spotbugsTest`, remain available for explicit analysis. SpotBugs compiles
the selected sources but does not execute them.

HTML and XML reports are written to `build/reports/spotbugs/main.html` and
`main.xml` in each plugin module. Existing core plugin findings are recorded in
`plugin/config/spotbugs/baseline.xml`; the Spring Boot plugin has no baseline.
These are existing warnings pending review and fixes. New findings fail the build.

Run `./gradlew spotbugsMain -PspotbugsBaseline=false` to include existing findings
in the reports; this command fails while findings remain. Fix actionable warnings
and remove their entries from the baseline. Do not regenerate the baseline to
accept new warnings, or enable `ignoreFailures` to bypass the quality gate. Any
necessary false-positive exclusion must identify the specific bug and affected
code and explain why it is safe.

### Coverage reports

The root build and both plugin modules apply `build.jacoco` from the `jacoco`
subproject of the included `build-logic` build. Its configuration lives in
`build-logic/jacoco/src/main/kotlin/build.jacoco.gradle.kts` and includes the
combined reports, per-module reports, and TestKit coverage setup.

The normal `build` and `check` tasks generate JaCoCo reports from unit and
Docker-free functional tests. Run `./gradlew jacocoTestReport` to run those tests
and generate reports directly. To target one module, use
`./gradlew :plugin:jacocoTestReport` or
`./gradlew :spring-boot-plugin:jacocoTestReport`.

| Scope | HTML report | XML report |
| --- | --- | --- |
| Both plugins | `build/reports/jacoco/test/html/index.html` | `build/reports/jacoco/test/jacocoTestReport.xml` |
| Core plugin | `plugin/build/reports/jacoco/test/html/index.html` | `plugin/build/reports/jacoco/test/jacocoTestReport.xml` |
| Spring Boot plugin | `spring-boot-plugin/build/reports/jacoco/test/html/index.html` | `spring-boot-plugin/build/reports/jacoco/test/jacocoTestReport.xml` |

`./gradlew jacocoIntegrationTestReport` runs the Docker integration suites and
generates separate reports under `reports/jacoco/integrationTest/` in each of
the same build directories, with XML named `jacocoIntegrationTestReport.xml`.
`./gradlew dockerTest` generates these reports too.
Running an `integrationTest` task directly also generates that module's
integration report. Integration data is kept out of the unit/functional reports.

The [TestKit JaCoCo extension](https://github.com/gmazzo/gradle-testkit-jacoco-plugin)
instruments copies of the plugin classes used by fixture builds. The shared
`GradleBuilds` helper collects coverage after each build, including configuration
cache reuse, into the owning test suite's execution-data file. Each test task
clears its own data before executing, so reruns do not accumulate old coverage.
The reports use the original production classes and sources; test fixtures and
generated Gradle classes are not part of the coverage totals. `jacocoVersion` in
`gradle.properties` pins the coverage engine. No minimum coverage threshold is
enforced.

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

Follow the [release guide](RELEASING.md) for prerequisites, version and changelog
preparation, verification, tagging, publication, and post-release work.

Both plugin modules apply `build.publishing` from the `publishing` subproject of
the included `build-logic` build. Its configuration lives in
`build-logic/publishing/src/main/kotlin/build.publishing.gradle.kts`. It applies
Maven Publish and the pinned Plugin Publish Plugin, sets the shared group and
version, configures website, SCM, description, and Apache 2.0 license metadata,
and makes `publishPlugins` depend on `check`. Module build scripts supply artifact
IDs, POM names, plugin declarations, and test-fixture publication exclusions.
The Plugin Portal website and Maven POM project URL point to the documentation
site; VCS and SCM links point to the GitHub repository.

The release version comes from `pluginVersion` in the root `gradle.properties`. The core artifact is `io.github.gregwhitaker:gradle-aws-emulator`; the companion is `io.github.gregwhitaker:gradle-aws-emulator-spring-boot` and depends on the core. Both use the shared version. Publishing includes the implementation JAR, sources, Javadoc, dependency metadata, and the plugin marker; test fixtures and the documentation subproject are not published. `publishPlugins` runs the plugin's unit tests, functional tests, and plugin validation before publishing.

### Verify the publication locally

The release workflow runs verification automatically. Use the
[optional local checks](RELEASING.md#optional-local-checks) to check metadata,
build local publications, and run smoke or Docker tests before pushing.

### Publish with GitHub Actions

Run `python3 scripts/release.py <version>` from a clean, synchronized `main`
checkout. It updates the version and changelog date, pushes the release commit,
waits for CI, and pushes the matching tag. See [make a release](RELEASING.md#make-a-release)
for setup, dry runs, and resuming. The workflow tests and publishes both plugins,
then creates a GitHub Release using the tagged changelog section.
See [after publishing](RELEASING.md#after-publishing) for release
notes, first-publication documentation updates, and the next development version.

### Publish from your machine

Use [failure recovery](RELEASING.md#if-publication-fails) if a release uploads
only one plugin. Check Portal status first, then publish only the missing module
from the same release tag after the Actions run finishes.

This project is licensed under the [Apache License 2.0](LICENSE).
