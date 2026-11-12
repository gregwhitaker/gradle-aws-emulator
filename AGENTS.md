# Repository guidance

These instructions apply throughout the `gradle-aws-emulator` repository.

## Project and layout

This project provides Gradle plugins for local AWS Emulator lifecycle management,
AWS resource provisioning, JVM test integration, and Spring Boot development.
Production code is Java 17; build scripts use the Gradle Kotlin DSL.

- `plugin/`: core plugin, `io.github.gregwhitaker.aws-emulator`.
- `spring-boot-plugin/`: optional companion, `io.github.gregwhitaker.aws-emulator.spring-boot`.
- `docs/`: AsciiDoc user guide, static site assets, Java API reference assembly, and link checker.
- `examples/`: independent composite builds that consume this checkout; run them with
  `./gradlew -p examples/<example> ...`, not as root subprojects.
- `gradle.properties`: shared plugin and documentation version (`pluginVersion`).
- `.github/workflows/ci.yml`: supported JDK/Gradle test matrix and Docker checks.

Read `README.md` for public behavior and `docs/README.md` for documentation authoring.

## Build and verification

Run commands from the repository root using `./gradlew` (`gradlew.bat` on Windows).
Use Java 17 or newer. The wrapper pins Gradle 8.14.3; the plugins require Gradle 8.14+.

| Command | Purpose |
| --- | --- |
| `./gradlew build` | Both plugins' unit/functional checks, plugin validation, and documentation generation, verification, and packaging; no Docker required. |
| `./gradlew :plugin:test` | Core unit tests. |
| `./gradlew :plugin:functionalTest :spring-boot-plugin:functionalTest` | Gradle TestKit checks without Docker. |
| `./gradlew dockerTest` | Both plugins' integration suites against a real emulator. |
| `./gradlew :docs:check` | Generate the site and verify local links, anchors, assets, and theme controls. |
| `./gradlew -p examples/basic integrationTest --configuration-cache` | Exercise the basic consumer against Docker; repeat to check configuration-cache reuse. |

Docker integration requires the `docker` CLI and a running local engine using Linux
containers. The first run downloads/builds the emulator image. No AWS account or
AWS CLI is required. `build` does not run the Docker integration suites.

Run checks appropriate to the change. Use `--tests '<fully.qualified.TestClass>'`
with a specific test task for focused verification. Run the relevant integration
suite for runtime, SDK, or lifecycle changes when Docker is available; report any
checks that could not run. Docker suites intentionally fail some nested fixture
builds to verify cleanup; the enclosing tests should pass.

## Implementation conventions

- Follow the existing Java formatting and feature-based packages under
  `io.github.gregwhitaker.awsemulator`. Keep each AWS service's module, task types,
  and operation/provisioning helpers together under `aws/<service>/`.
- Keep task registration in modules, runtime ownership in `AwsEmulatorService`,
  and Docker/process details under `internal/`. Keep Spring Boot integration in
  the companion module. Packages named `internal` are implementation details.
- Register and configure tasks lazily. Use Gradle `Property`, collection properties,
  file properties, and providers with appropriate task input/output annotations.
  Preserve configuration-cache compatibility and avoid capturing `Project` in
  execution-time actions.
- Applying the plugin must not start Docker or opt existing tasks into the emulator.
  Resolve connections, credentials, and generated TLS files during execution.
- Preserve the separate lifecycles: managed instances are shared by participating
  tasks within a project and cleaned up after use, including failures; development
  instances persist until explicitly stopped. Custom resource tasks default to
  `MANAGED`, while module convenience tasks target `DEVELOPMENT`.
- Tasks operating on live emulator state must remain excluded from up-to-date
  skipping and build-cache reuse. Configuration-cache reuse is supported.
- Use `AwsClientFactory` for resource-task SDK clients and close them after use.
  Preserve explicit local endpoints, emulator credentials, and certificate/hostname
  validation. Keep connection settings scoped to consumers and clients rather than
  changing the Gradle daemon's credentials or global TLS settings.

## Tests and documentation

- Use JUnit 5 and mirror production packages. Put isolated tests in `src/test/`,
  Docker-free TestKit tests in `src/functional/`, and real emulator tests in
  `src/integration/` within the relevant module.
- Reuse `GradleBuilds` from `plugin/src/testFixtures/` for TestKit setup; it enables
  configuration-cache checks with problems treated as failures. For compatibility
  checks, use `-PtestGradleVersion=<version>` or
  `-PtestGradleInstallation=/path/to/gradle`.
- Update the README, relevant AsciiDoc guide, and runnable examples when public
  task names, properties, defaults, or behavior change. Document public Java APIs.
- Edit guide sources in `docs/src/docs/asciidoc/`, assets in `docs/src/site/`, and
  API templates in `docs/src/templates/`. Add new guide pages to the shared
  navigation as described in `docs/README.md`.
- Keep generated `build/`, `.gradle/`, and `.aws-emulator/` contents out of changes.
