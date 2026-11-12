# AWS Emulator Gradle Plugin

[![Build](https://github.com/gregwhitaker/gradle-aws-emulator/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/gregwhitaker/gradle-aws-emulator/actions/workflows/ci.yml)

Run [AWS Emulator](https://github.com/aws/aws-emulator) from Gradle, provision local AWS resources, and connect your tests and applications over HTTPS. The plugin manages the container, credentials, and TLS configuration for participating tasks.

[User guide](docs/src/docs/asciidoc/index.adoc) · [Examples](examples/README.md) · [Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md)

## Requirements

- Java 17+ and Gradle 8.14+.
- The `docker` CLI and a running local Docker engine using Linux containers. AMD64 and ARM64 are supported.

No AWS account or AWS CLI is required. On first use, the plugin builds an image from the pinned AWS Emulator package; later runs reuse it. Applying the plugin alone does not start Docker or change existing test tasks.

## Quick start

### Try the included example

From this repository's root:

```sh
./gradlew -p examples/basic integrationTest --configuration-cache
```

The example provisions S3 buckets and SQS queues, loads message fixtures, verifies signed AWS SDK requests, and removes its managed container. Run it again to check configuration-cache reuse. See the [basic example](examples/basic/README.md) for the complete build and failure-cleanup demonstration.

### Apply from a checkout

Version **0.2.0** is implemented in this repository and has not been published to the Gradle Plugin Portal. Include the checkout in your application's `settings.gradle.kts`, adjusting the path as needed:

```kotlin
pluginManagement {
    includeBuild("../gradle-aws-emulator")
}
```

Apply the plugin and opt in an existing task in `build.gradle.kts`:

```kotlin
plugins {
    java
    id("io.github.gregwhitaker.aws-emulator")
}

awsEmulator {
    port.set(0)
    useWith(tasks.named<Test>("test"))
}
```

Add `.aws-emulator/` to your application's `.gitignore`, then run `./gradlew test`. Your application supplies its own AWS SDK and test dependencies. `port.set(0)` selects an available port for concurrent builds or simultaneous managed and development instances.

Use an SDK that supports external endpoint configuration and its normal credential providers. Explicit client settings can override the supplied connection; see [credentials and TLS](docs/src/docs/asciidoc/connections.adoc) for SDK configuration and custom trust stores. The [getting-started guide](docs/src/docs/asciidoc/getting-started.adoc) also includes Groovy DSL setup.

## Runtime modes

| Mode | Use it for | Lifetime |
| --- | --- | --- |
| **Managed** | Tests and other tasks attached with `awsEmulator.useWith(...)`. Custom resource tasks also default to this mode. | Starts on demand, is shared by participating tasks in the project, and is removed after use, including ordinary task failures. |
| **Development** | Interactive work with `startAwsEmulator`, module convenience tasks, or explicitly selected `EmulatorMode.DEVELOPMENT`. | Persists across Gradle invocations until `stopAwsEmulator` or `restartAwsEmulator`. |

Managed and development instances have separate containers and connection files. AWS resources live in memory: stopping or restarting an instance discards its data. Managed cleanup saves container logs under `.aws-emulator/logs/` by default.

`useWith` accepts existing `Test`, `JavaExec`, and `Exec` tasks, including subclasses. It supplies connection settings at execution time. Participating tasks with work run on every invocation; configuration-cache reuse is supported, while up-to-date skipping and build-cache reuse are disabled. Skipped or `NO-SOURCE` consumers do not start an emulator themselves, though their provisioning dependencies can still run.

Projects get separate runtimes by default. Use [shared runtime ownership](docs/src/docs/asciidoc/shared-runtimes.adoc) to connect multiple projects to one instance. Tasks sharing an instance also share resources, so use distinct names for concurrent tests. See [tests and process execution](docs/src/docs/asciidoc/testing.adoc) for setup dependencies, mode selection, and Python/Node integration.

## Provision resources

Register typed tasks and attach them to the consumer that needs their resources. This `build.gradle.kts` provisions a bucket and queue before tests run:

```kotlin
import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketTask
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask

plugins {
    java
    id("io.github.gregwhitaker.aws-emulator")
}

val createBucket = tasks.register<CreateS3BucketTask>("createTestBucket") {
    bucketName.set("application-test-data")
}
val createQueue = tasks.register<CreateSqsQueueTask>("createTestQueue") {
    queueName.set("application-test-events")
}
val tests = tasks.named<Test>("test") {
    dependsOn(createBucket, createQueue)
}

awsEmulator {
    port.set(0)
    useWith(tests)
}
```

The resource tasks and tests use the same managed instance. For reusable definitions across tests, application runs, and named development scenarios, declare `awsEmulator.resourceSets`; see [data fixtures and scenarios](docs/src/docs/asciidoc/scenarios.adoc).

| Resources | Guide |
| --- | --- |
| S3 buckets, SQS queues, FIFO queues, dead-letter queues, and message fixtures | [S3 and SQS](docs/src/docs/asciidoc/resources.adoc) |
| SNS topics, SQS subscriptions, and notifications | [SNS](docs/src/docs/asciidoc/sns.adoc) |
| DynamoDB tables, indexes, and Java initializers | [DynamoDB](docs/src/docs/asciidoc/dynamodb.adoc) |
| Cognito User Pools, public app clients, user/group fixtures, and runtime ID exports | [Cognito](docs/src/docs/asciidoc/cognito.adoc) |
| S3 object uploads/downloads, DynamoDB item fixtures, and development datasets | [Data fixtures and scenarios](docs/src/docs/asciidoc/scenarios.adoc) |
| SSM Parameter Store, Secrets Manager, EventBridge, Kinesis, KMS, and CloudWatch Logs | [Additional services](docs/src/docs/asciidoc/services.adoc) |

These guides cover task properties, Kotlin/Groovy configuration, fixture formats, and resource reuse and deletion behavior.

The [Cognito example](examples/cognito/README.md) verifies password sign-in, token refresh, and Spring Security authorization. Pinned AWS Emulator `1.0.322` signs JWTs with ES256 while AWS Cognito uses RS256; the example explicitly configures the local algorithm while preserving signature, issuer, expiry, client, and TLS validation.

## Local development

From this repository, use the basic example to start and inspect a persistent instance:

```sh
./gradlew -p examples/basic startAwsEmulator
./gradlew -p examples/basic statusAwsEmulator
./gradlew -p examples/basic logsAwsEmulator
./gradlew -p examples/basic stopAwsEmulator
```

In your own application, omit `-p examples/basic`.

| Task | Purpose |
| --- | --- |
| `prepareAwsEmulator` | Build or pull the emulator image ahead of time. |
| `startAwsEmulator` | Start or reuse the development instance and wait for HTTPS readiness. |
| `stopAwsEmulator` | Remove the development container and its generated connection files. |
| `restartAwsEmulator` | Restart with current settings and empty state. |
| `statusAwsEmulator` | Show the development container's state, health, and endpoint. |
| `logsAwsEmulator` | Print the last 100 lines of development-container logs. |
| `doctorAwsEmulator` | Diagnose Docker, images, ports, owned containers, and connection health. |
| `cleanupAwsEmulator` | Archive logs and remove stale managed containers; `--report-only` previews cleanup. |
| `exportAwsEmulatorConnection` | Write shell and PowerShell connection scripts for development tools and IDEs. |

Changed runtime settings require `restartAwsEmulator`. A hard kill or Docker failure can leave a managed container behind; use `doctorAwsEmulator` and `cleanupAwsEmulator` to inspect and remove it. Cleanup preserves active builds and development instances.

See the [lifecycle guide](docs/src/docs/asciidoc/lifecycle.adoc) for ownership and cleanup rules, [connection exports](docs/src/docs/asciidoc/connections.adoc#shell-exports) for shell and IDE setup, and [configuration reference](docs/src/docs/asciidoc/configuration.adoc) for ports, regions, images, timeouts, and defaults.

### Spring Boot

Apply **`io.github.gregwhitaker.aws-emulator.spring-boot`** alongside your application's `org.springframework.boot` plugin. The companion applies the core plugin and adds `bootRunLocal` and `bootRunLocalDebug`, with automatic resource creation before startup.

```sh
./gradlew -p examples/spring-boot bootRunLocal --configuration-cache
./gradlew -p examples/spring-boot bootRunLocalDebug
./gradlew -p examples/spring-boot stopAwsEmulator
```

The local tasks use a persistent development instance by default. The debug task waits for a debugger on `localhost:5005`. See the [companion README](spring-boot-plugin/README.md) for installation, profiles, resource selection, and managed mode.

Micronaut and Ktor use the core plugin with their existing `run` and `test` tasks; see the [framework guide](docs/src/docs/asciidoc/frameworks.adoc).

## Examples

Each example is an independent composite build that uses this checkout. Run commands from the repository root with `./gradlew -p examples/<example> ...`.

| Example | Demonstrates |
| --- | --- |
| [Basic](examples/basic/README.md) | S3, standard/FIFO SQS queues, a dead-letter queue, message fixtures, and STS requests. |
| [Data fixtures and scenarios](examples/scenarios/README.md) | S3 files, DynamoDB items, reusable resource sets, and development reset/reseed. |
| [DynamoDB](examples/dynamodb/README.md) | Indexes, stream settings, and a Java initializer in `buildSrc`. |
| [Additional services](examples/services/README.md) | Configuration, secrets, events, streams, encryption, and logs. |
| [Cognito](examples/cognito/README.md) | User Pools, user/group fixtures, runtime ID exports, and signed JWT authorization with Spring Security. |
| [External processes](examples/processes/README.md) | Python, Node, AWS CLI, and shell/IDE connection exports. |
| [Multi-project](examples/multi-project/README.md) | Shared runtime ownership and resources across applications. |
| [Spring Boot](examples/spring-boot/README.md) | Local run/debug tasks, profiles, and SQS provisioning. |
| [Spring Cloud AWS](examples/spring-cloud-aws/README.md) | SQS listeners, async clients, and SSM/Secrets Manager configuration imports. |
| [Micronaut](examples/micronaut/README.md) | Injected SQS clients and framework tests. |
| [Ktor](examples/ktor/README.md) | An HTTP/SQS route and `testApplication`. |

## Documentation

The [user guide](docs/src/docs/asciidoc/index.adoc) covers configuration, resource tasks, integrations, and [troubleshooting](docs/src/docs/asciidoc/troubleshooting.adoc). Generate the HTML guide and Java API reference locally:

```sh
./gradlew :docs:site
```

Open `docs/build/site/index.html`. Site generation requires Java and Gradle, with no Docker or separate frontend toolchain. See [docs/README.md](docs/README.md) for link checks, packaging, authoring, and GitHub Pages deployment.

## Build and contribute

```sh
./gradlew build
./gradlew dockerTest
```

`build` runs both plugins' unit and functional checks, plugin validation, and documentation generation, verification, and packaging. `dockerTest` runs both plugins' integration suites against a real emulator and requires Docker.

See [Contributing](CONTRIBUTING.md) for repository layout, coding conventions, and focused verification commands. Maintainers can find publication checks and release steps in [Publish to the Gradle Plugin Portal](CONTRIBUTING.md#publish-to-the-gradle-plugin-portal).

## License

[Apache License 2.0](LICENSE).
