# AWS Emulator Gradle Plugin

[![Build](https://github.com/gregwhitaker/gradle-aws-emulator/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/gregwhitaker/gradle-aws-emulator/actions/workflows/ci.yml)

Run [AWS Emulator](https://github.com/aws/aws-emulator) from your Gradle build. Provision AWS resources before tests or keep an instance running for local development. The plugin supplies the endpoint, credentials, and HTTPS trust, and cleans up managed instances after use.

[Quick start](#quick-start) · [Local development](#local-development) · [Examples](#examples) · [User guide](https://gregwhitaker.github.io/gradle-aws-emulator/) · [Contributing](CONTRIBUTING.md) · [Releasing](RELEASING.md)

## Requirements

- Java 17+ and Gradle 8.14+.
- The `docker` CLI and a Docker engine using Linux containers on AMD64 or ARM64. Local and [remote Docker](#containerized-ci-and-remote-docker) are supported. You can also connect to an [existing emulator](#connect-to-an-existing-emulator) without Docker.

No AWS account or AWS CLI is required. The first emulator run builds the pinned image; later runs reuse it. Applying the plugin alone does not start Docker or attach it to existing tasks.

## Quick start

### Try the included example

Run from this repository's root:

```sh
./gradlew -p examples/basic integrationTest --configuration-cache
```

The [basic example](examples/basic/README.md) provisions S3 buckets and SQS queues, loads message fixtures, verifies signed AWS SDK requests, and removes its container. Run it again to check configuration-cache reuse.

### Apply from a checkout

Version **0.2.0** is unreleased. Include this checkout in your application's `settings.gradle.kts`, adjusting the path as needed:

```kotlin
pluginManagement {
    includeBuild("../gradle-aws-emulator")
}
```

In `build.gradle.kts`, apply the plugin and create a queue before your tests run:

```kotlin
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
import io.github.gregwhitaker.awsemulator.model.EmulatorMode

plugins {
    java
    id("io.github.gregwhitaker.aws-emulator")
}

val createQueue = tasks.register<CreateSqsQueueTask>("createTestQueue") {
    queueName.set("application-events")
}

val tests = tasks.named<Test>("test")

awsEmulator {
    useWith(tests, EmulatorMode.MANAGED, createQueue)
}
```

Add `.aws-emulator/` to your application's `.gitignore`, then run `./gradlew test`. The queue and tests share one managed instance, which is cleaned up after use. The plugin selects an available port; use `-PawsEmulator.port=9297` for a fixed port.

For a separate integration suite, attach its existing `integrationTest` task instead so unit tests run independently of the emulator. The basic example shows the complete setup.

Keep your application's AWS SDK and test dependencies. Use an SDK that supports external endpoint configuration and its normal credential providers; explicit client settings can override the supplied connection. See [connection and TLS setup](docs/src/docs/asciidoc/connections.adoc), [Groovy DSL setup](docs/src/docs/asciidoc/getting-started.adoc), and [configuration](docs/src/docs/asciidoc/configuration.adoc) for further options.

## Runtime modes

| Mode | Used by | Cleanup |
| --- | --- | --- |
| **Managed** | `awsEmulator.useWith(...)` and custom resource tasks, by default. | Automatic after participating tasks finish, including ordinary task failures. |
| **Development** | `startAwsEmulator`, module convenience tasks, or explicit `EmulatorMode.DEVELOPMENT`. | Explicit `stopAwsEmulator`; the instance survives Gradle and application exits. |

Managed instances start fresh and are shared by participating tasks selecting the same runtime name within a project. Development instances are separate. **Stopping or restarting either instance discards its in-memory AWS resources.** Managed cleanup saves container logs under the selected runtime's working directory.

Custom resource tasks default to `MANAGED`. Set `awsEmulator.defaultMode` to change their default across the project's runtime names; explicit task `mode` values and `--mode` take precedence. Convenience tasks stay in development mode, resource sets use their attachment's mode, and Spring Boot uses `awsEmulator.springBoot.mode`.

`useWith` accepts existing `Test`, `JavaExec`, and `Exec` tasks. Configure consumer modes separately, for example with `useWith(task, EmulatorMode.DEVELOPMENT)` or `useWith(task, defaultMode)`. Setup tasks and their local resource prerequisites must use the consumer's mode; mismatches fail before setup runs. [Reusable resource sets](docs/src/docs/asciidoc/scenarios.adoc#resource-sets) let tests and development consumers share definitions with the appropriate mode.

Configuration-cache reuse is supported. Tasks that access emulator state always execute when they have work and do not reuse build-cache results. See [tests and process execution](docs/src/docs/asciidoc/testing.adoc) for setup, execution, and cleanup rules.

## Local development

Start and inspect a persistent instance using the basic example:

```sh
./gradlew -p examples/basic startAwsEmulator
./gradlew -p examples/basic statusAwsEmulator
./gradlew -p examples/basic logsAwsEmulator
./gradlew -p examples/basic stopAwsEmulator
```

In your own application, omit `-p examples/basic`. Use `restartAwsEmulator` after changing runtime settings. See the [lifecycle guide](docs/src/docs/asciidoc/lifecycle.adoc) for all commands, [connection exports](docs/src/docs/asciidoc/connections.adoc#shell-exports) for shell and IDE applications, and [troubleshooting](docs/src/docs/asciidoc/troubleshooting.adoc) for startup failures or interrupted builds.

### Resource reports

Run `./gradlew reportAwsEmulator` to inspect an existing development instance. It writes a standalone HTML report (`index.html`) and JSON inventory (`report.json`) under `build/reports/aws-emulator/default/development/reportAwsEmulator/`. Select a named runtime with `--runtime integration`. The report never starts an owned emulator; start or provision it first.

Reports cover all [13 service modules](#aws-resources), including resource settings and relationships. They omit payloads, secret values, log events, Cognito users/client secrets, and email/template contents.

Unsupported metadata APIs are recorded without discarding other sections; `--fail-on-error` fails after writing both files if any section is incomplete. Emulator `1.0.367` supports SES v2 template listing, including template metadata in the inventory.

For managed test snapshots, register a `ReportAwsEmulatorTask` as a test finalizer. The [basic](examples/basic/README.md#resource-reports) and [services](examples/services/README.md#resource-reports) examples demonstrate this with `-PemulatorReport`, including capture after test failures. See the [report guide](docs/src/docs/asciidoc/reports.adoc) for configuration and coverage.

### Spring Boot

The optional **`io.github.gregwhitaker.aws-emulator.spring-boot`** companion adds `bootRunLocal` and `bootRunLocalDebug`, with resource creation before startup. Apply it alongside your chosen `org.springframework.boot` version; it applies the core emulator plugin for you.

```sh
./gradlew -p examples/spring-boot bootRunLocal --configuration-cache
./gradlew -p examples/spring-boot bootRunLocalDebug
./gradlew -p examples/spring-boot stopAwsEmulator
```

These tasks use a persistent development instance by default. The debug task waits for a debugger on `localhost:5005`. See the [companion README](spring-boot-plugin/README.md) for installation, profiles, resource selection, and managed mode.

[Micronaut](docs/src/docs/asciidoc/micronaut.adoc) and [Ktor](docs/src/docs/asciidoc/ktor.adoc) use the core plugin with existing `run` and `test` tasks. [Spring Cloud AWS](docs/src/docs/asciidoc/spring-cloud-aws.adoc) uses the companion for auto-configured clients, SQS listeners, and configuration imports.

## AWS resources

Each service guide includes task references and configuration examples:

| Service | Resources and operations |
| --- | --- |
| [CloudFormation](docs/src/docs/asciidoc/cloudformation.adoc) | Stack deployment, template validation, and output exports. |
| [CloudWatch Logs](docs/src/docs/asciidoc/cloudwatch-logs.adoc) | Log groups, streams, and events. |
| [Cognito](docs/src/docs/asciidoc/cognito.adoc) | User pools, app clients, and user/group fixtures. |
| [DynamoDB](docs/src/docs/asciidoc/dynamodb.adoc) | Tables, indexes, streams, data fixtures, and configuration drift checks. |
| [EventBridge](docs/src/docs/asciidoc/eventbridge.adoc) | Event buses, rules, and targets. |
| [Kinesis](docs/src/docs/asciidoc/kinesis.adoc) | Streams and record fixtures. |
| [KMS](docs/src/docs/asciidoc/kms.adoc) | Keys, aliases, and encryption. |
| [S3](docs/src/docs/asciidoc/s3.adoc) | Buckets, objects, policies, versioning, and notifications. |
| [Secrets Manager](docs/src/docs/asciidoc/secrets-manager.adoc) | Secrets and fixture values. |
| [SES](docs/src/docs/asciidoc/ses.adoc) | Email identities, templates, and simulated delivery events. |
| [SNS](docs/src/docs/asciidoc/sns.adoc) | Topics, subscriptions, and notifications. |
| [SQS](docs/src/docs/asciidoc/sqs.adoc) | Queues, message fixtures, and queue deletion. |
| [SSM Parameter Store](docs/src/docs/asciidoc/parameter-store.adoc) | Parameters and application configuration. |

Use [resource sets and scenarios](docs/src/docs/asciidoc/scenarios.adoc) to reuse definitions across tests, local applications, and development environments. The [DynamoDB example](examples/dynamodb/README.md) also demonstrates safe table reuse: configuration drift fails with requested and existing values while preserving the table and its items.

Declare resource-set `cleanup` steps and run `resetAwsEmulator -PawsEmulator.resetScope=RESOURCES` to clear and reseed just those resources while keeping the runtime and unrelated resources. `cleanAwsEmulator` runs cleanup alone. The default reset scope remains `RUNTIME`, which discards the entire selected development instance.

Capture current S3 objects with `ExportS3ObjectsTask` and DynamoDB items with `ExportDynamoDbItemsTask`, then replay with `ReplayS3ObjectsTask` and `PutDynamoDbItemsTask`. These portable fixtures contain data, support binary and typed values, and require resource definitions for recreation. See [export and replay](docs/src/docs/asciidoc/scenarios.adoc#export-and-replay) and the [scenario example](examples/scenarios/README.md).

### CloudFormation

`DeployCloudFormationStackTask` deploys local JSON or YAML templates, waits for completion, and exports stack outputs as JSON. Every resource declaration is checked against provisioning handlers audited for emulator **1.0.367**; types that merely report success are rejected before the task connects.

Run `./gradlew validateCloudFormationTemplate --templateFile=infra/test-stack.yaml` to validate without Docker. Transforms (including SAM), nested stacks, and custom providers must be expanded or replaced with explicit resource tasks. See the [CloudFormation guide](docs/src/docs/asciidoc/cloudformation.adoc) for supported types and limits, or the [runnable example](examples/cloudformation/README.md) for S3, SQS, and DynamoDB provisioning.

## Advanced runtimes

### Named runtimes

Give suites independent AWS state and settings within one project. Each runtime name has separate managed and development instances, an automatic port, and a working directory. Existing configuration uses `default`.

Select a name through `useWith`, a resource task's `runtimeName`, or lifecycle commands such as `startAwsEmulatorIntegration` and `stopAwsEmulator --runtime integration`. See the [named runtime guide](docs/src/docs/asciidoc/named-runtimes.adoc) and [example](examples/named-runtimes/README.md) for registration and suite setup.

### Containerized CI and remote Docker

Set `awsEmulator.docker.transport` to `DockerTransport.TUNNEL` when Gradle runs in a container or connects to a remote Docker host. The plugin copies connection files through Docker and exposes verified HTTPS on the runner's loopback interface, without published container ports or shared filesystem paths.

Docker's context/environment selects the daemon unless `host` or `context` is set explicitly. `TUNNEL` supports managed instances and cleans up containers, tunnels, and copied credentials after use. `DIRECT` is the default and also supports persistent development instances. See the [remote Docker guide](docs/src/docs/asciidoc/remote-docker.adoc) and the [named](examples/named-runtimes/README.md) and [shared](examples/multi-project/README.md) examples.

### Connect to an existing emulator

Configure `awsEmulator.external` with an HTTPS endpoint, credentials file, and PEM CA certificate. The plugin connects without invoking Docker or managing the emulator's lifecycle. Both modes use the same external instance; resource changes persist after successful and failed builds, and lifecycle commands are rejected.

See the [external connection guide](docs/src/docs/asciidoc/external-emulators.adoc) and [example](examples/external/README.md) for configuration.

### Shared runtimes and Isolated Projects

Use [shared runtime ownership](docs/src/docs/asciidoc/shared-runtimes.adoc) to connect multiple projects to one instance. Tasks sharing an instance also share resources, so use distinct names for concurrent tests.

Gradle Isolated Projects is supported for default, named, external, and shared runtimes. See the [isolation guide](docs/src/docs/asciidoc/shared-runtimes.adoc#isolated-projects) for Gradle flags, build-script requirements, and the Spring Boot compatibility limitation.

## Examples

Browse the [example catalogue](examples/README.md) for complete builds covering services, frameworks, data fixtures, external processes, and multi-project setups. Examples are independent builds; run them from the repository root with `./gradlew -p examples/<example> ...`.

## Documentation

The [documentation website](https://gregwhitaker.github.io/gradle-aws-emulator/) includes the user guide and Java API reference. See [documentation authoring](docs/README.md) to build and browse the site locally.

## Build and contribute

Run checks from the repository root:

| Command | Purpose |
| --- | --- |
| `./gradlew build` | Both plugins' unit/functional tests, plugin validation, code quality checks, documentation, and coverage reports. No Docker required. |
| `./gradlew dockerTest` | Both plugins' integration tests against Docker. |
| `./gradlew qualityCheck` | Formatting, license headers, and SpotBugs; no tests or Docker. |
| `./gradlew spotlessApply` | Fix source whitespace and final newlines. |
| `./gradlew licenseFormat` | Apply Apache 2.0 headers to project-owned source files. |
| `./gradlew jacocoTestReport` | Generate combined and per-plugin unit/functional coverage. |

See [Contributing](CONTRIBUTING.md) for development setup, [code quality checks](CONTRIBUTING.md#code-quality-checks), and [coverage reports](CONTRIBUTING.md#coverage-reports). Release both plugins with `python3 scripts/release.py <version>`; the [release guide](RELEASING.md) covers setup, dry runs, and recovery. The [changelog](CHANGELOG.md) tracks version history.

The tested emulator version is pinned in [`gradle/emulator.properties`](gradle/emulator.properties). Upgrade PRs run compatibility checks and require maintainer review, including a separate CloudFormation provisioning audit; see the [upgrade procedure](CONTRIBUTING.md#aws-emulator-upgrades).

## License

Copyright 2026 Greg Whitaker. Licensed under the [Apache License, Version 2.0](LICENSE).
