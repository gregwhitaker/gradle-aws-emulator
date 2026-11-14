# AWS Emulator Gradle Plugin

[![Build](https://github.com/gregwhitaker/gradle-aws-emulator/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/gregwhitaker/gradle-aws-emulator/actions/workflows/ci.yml)

Run [AWS Emulator](https://github.com/aws/aws-emulator) from your Gradle build. Provision AWS resources before tests, keep an instance running for local development, and let the plugin supply the endpoint, credentials, and HTTPS trust.

[Quick start](#quick-start) · [Examples](#examples) · [User guide](https://gregwhitaker.github.io/gradle-aws-emulator/) · [Contributing](CONTRIBUTING.md)

## Requirements

- Java 17+ and Gradle 8.14+.
- The `docker` CLI and a running local Docker engine using Linux containers, or a connection to an [existing emulator](#connect-to-an-existing-emulator). AMD64 and ARM64 are supported for Docker runtimes.

No AWS account or AWS CLI is required. The first emulator run builds the pinned image; subsequent runs reuse it. Applying the plugin alone does not start Docker or opt existing tasks into the emulator.

## Quick start

### Try the included example

From this repository's root:

```sh
./gradlew -p examples/basic integrationTest --configuration-cache
```

The example provisions S3 buckets and SQS queues, loads message fixtures, verifies signed AWS SDK requests, and removes its container. Run it again to check configuration-cache reuse.

### Apply from a checkout

Version **0.2.0** is unreleased. To use it in an existing application, include this checkout in `settings.gradle.kts`, adjusting the path as needed:

```kotlin
pluginManagement {
    includeBuild("../gradle-aws-emulator")
}
```

In `build.gradle.kts`, apply the plugin and create a queue before your existing tests run:

```kotlin
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask

plugins {
    java
    id("io.github.gregwhitaker.aws-emulator")
}

val createQueue = tasks.register<CreateSqsQueueTask>("createTestQueue") {
    queueName.set("application-events")
}

val tests = tasks.named<Test>("test") {
    dependsOn(createQueue)
}

awsEmulator {
    port.set(0)
    useWith(tests)
}
```

Add `.aws-emulator/` to your application's `.gitignore`, then run `./gradlew test`. The queue and tests share one managed instance, which is cleaned up after the build. `port.set(0)` selects an available port.

Keep your application's AWS SDK and test dependencies. Use an SDK that supports external endpoint configuration and its normal credential providers; explicit client settings can override the supplied connection. The [basic example](examples/basic/README.md) shows a complete build, and the [connection guide](docs/src/docs/asciidoc/connections.adoc) covers SDK setup and TLS.

For Groovy DSL setup, see [getting started](docs/src/docs/asciidoc/getting-started.adoc). For regions, ports, images, and timeouts, see [configuration](docs/src/docs/asciidoc/configuration.adoc).

## Runtime modes

| Mode | Starts with | Cleanup |
| --- | --- | --- |
| **Managed** | `awsEmulator.useWith(...)` or a custom resource task, by default. | Automatic after participating tasks finish, including ordinary task failures. |
| **Development** | `startAwsEmulator`, module convenience tasks, or `EmulatorMode.DEVELOPMENT`. | Explicit `stopAwsEmulator`; the instance survives Gradle and application exits. |

Managed instances start fresh and are shared by participating tasks selecting the same runtime name in each project. Development instances are separate. **Stopping or restarting either instance discards its in-memory AWS resources.** Managed cleanup saves container logs under the selected runtime's working directory.

`useWith` accepts existing `Test`, `JavaExec`, and `Exec` tasks. Configuration-cache reuse is supported. Tasks that access emulator state opt out of up-to-date checks and build-cache reuse; see [tests and process execution](docs/src/docs/asciidoc/testing.adoc) for setup dependencies and execution rules.

Use [shared runtime ownership](docs/src/docs/asciidoc/shared-runtimes.adoc) to connect multiple projects to one instance. Tasks sharing an instance also share resources, so use distinct names for concurrent tests.

Gradle Isolated Projects is supported, including default, named, external, and shared runtimes. With Gradle 8.14–9.6, enable it using `-Dorg.gradle.unsafe.isolated-projects=true`; Gradle 9.7+ uses `--isolated-projects`. See the [isolation guide](docs/src/docs/asciidoc/shared-runtimes.adoc#isolated-projects) for build-script requirements and the Spring Boot compatibility limitation.

## Named runtimes

Give suites independent AWS state and settings within one project:

```kotlin
awsEmulator {
    runtimes {
        register("integration")
        register("acceptance") { region.set("eu-west-1") }
    }
    useWith(tasks.named<Test>("integrationTest"), "integration")
    useWith(tasks.named<Test>("acceptanceTest"), "acceptance")
}
```

Each name has its own managed and development instances, automatic port, and working directory. Existing configuration and consumers keep using `default`. Select names on resource tasks with `runtimeName`, or use lifecycle commands such as `startAwsEmulatorIntegration` and `stopAwsEmulator --runtime integration`. Resource sets, scenarios, external connections, shared owners, and Spring Boot support named runtimes.

See the [named runtime guide](docs/src/docs/asciidoc/named-runtimes.adoc) for inheritance and selection rules, and the [runnable example](examples/named-runtimes/README.md) for complete suite setup.

## Connect to an existing emulator

Supply its HTTPS endpoint, credentials file, and PEM CA certificate:

```kotlin
awsEmulator {
    external {
        endpoint.set("https://localhost:9297")
        credentialsFile.set(layout.projectDirectory.file("local-emulator/emulator_credentials"))
        certificateFile.set(layout.projectDirectory.file("local-emulator/ca.pem"))
    }
    useWith(tasks.named<Test>("test"))
}
```

The plugin connects without invoking Docker or managing the emulator's lifecycle. Both runtime modes use the same external instance; resource changes persist after successful and failed builds. Lifecycle commands are rejected. Resource tasks, scenario seeding, Spring Boot, connection exports, and configuration-cache reuse are supported.

See the [external connection guide](docs/src/docs/asciidoc/external-emulators.adoc) and [runnable example](examples/external/README.md).

## Local development

From this repository, use the basic example to start and inspect a persistent instance:

```sh
./gradlew -p examples/basic startAwsEmulator
./gradlew -p examples/basic statusAwsEmulator
./gradlew -p examples/basic logsAwsEmulator
./gradlew -p examples/basic stopAwsEmulator
```

In your own application, omit `-p examples/basic`. Use `restartAwsEmulator` after changing runtime settings. The [lifecycle guide](docs/src/docs/asciidoc/lifecycle.adoc) lists all lifecycle and diagnostic tasks.

For applications launched outside Gradle, use [shell and IDE connection exports](docs/src/docs/asciidoc/connections.adoc#shell-exports). For startup failures or containers left after an interrupted build, see [troubleshooting](docs/src/docs/asciidoc/troubleshooting.adoc).

### Spring Boot

The optional **`io.github.gregwhitaker.aws-emulator.spring-boot`** companion adds `bootRunLocal` and `bootRunLocalDebug`, with automatic resource creation before startup. Apply it alongside your chosen `org.springframework.boot` version; it applies the core emulator plugin for you.

```sh
./gradlew -p examples/spring-boot bootRunLocal --configuration-cache
./gradlew -p examples/spring-boot bootRunLocalDebug
./gradlew -p examples/spring-boot stopAwsEmulator
```

The local tasks use a persistent development instance by default. The debug task waits for a debugger on `localhost:5005`. See the [companion README](spring-boot-plugin/README.md) for installation, profiles, resource selection, and managed mode.

Micronaut and Ktor use the core plugin with their existing `run` and `test` tasks; see the [framework guide](docs/src/docs/asciidoc/frameworks.adoc).

## Examples

Browse the [examples](examples/README.md) and follow the included instructions to run them locally.

## Standalone Testcontainers

The separate [`testcontainers-aws-emulator`](testcontainers/README.md) Java library runs AWS Emulator directly from JUnit, IDEs, or other Java test runners. It provides an `AwsEmulatorContainer` with generated credentials, verified HTTPS, and automatic cleanup. It requires Java 17+ and Testcontainers 2.0.5, and has no Gradle API dependency.

Try the checkout example with `./gradlew -p examples/testcontainers test --configuration-cache`. Version 0.2.0 is unreleased; see the module README for local publication. Both libraries share image, credential, TLS, and health support through [`aws-emulator-core`](emulator-core/README.md). The Gradle plugin retains its existing managed and development lifecycles and does not depend on Testcontainers.

## Documentation

See the [documentation website](https://gregwhitaker.github.io/gradle-aws-emulator/) for the user guide and Java API reference.

## Build and contribute

```sh
./gradlew build       # Unit/functional tests, plugin validation, and documentation
./gradlew dockerTest  # Integration tests against Docker
./gradlew licenseFormat  # Apply Apache 2.0 headers to project-owned source files
./gradlew jacocoTestReport  # Unit/functional coverage for both plugins
```

`build` also checks license headers. Run `./gradlew licenseCheck` to check them separately.

`build` generates JaCoCo HTML and XML reports for each plugin and a combined report.
Open `build/reports/jacoco/test/html/index.html` for combined unit and functional
coverage. See [coverage instructions](CONTRIBUTING.md#coverage-reports) for module
reports and Docker integration coverage.

The build and Docker checks cover the core and Spring Boot plugins; `build` does not require Docker. See [Contributing](CONTRIBUTING.md) for development setup and focused checks, [release instructions](CONTRIBUTING.md#publish-to-the-gradle-plugin-portal) for publishing, and the [changelog](CHANGELOG.md) for version history.

## License

Copyright 2026 Greg Whitaker

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
