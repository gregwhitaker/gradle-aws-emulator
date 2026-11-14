# Changelog

## 0.2.0 — Unreleased

- Add the standalone `testcontainers-aws-emulator` Java library with verified HTTPS, generated credentials, lazy pinned images, and disposable container cleanup. Extract `aws-emulator-core` support shared with the plugin while retaining its existing lifecycle; include a JUnit example, Maven publications, and Docker verification.
- Support Gradle Isolated Projects for default, named, external, and shared runtimes. Resolve owner metadata through project dependencies and shared setup through task paths; verify parallel configuration, configuration-cache reuse, and Docker lifecycle cleanup under isolation.
- Add named runtimes per project with independent settings, state, ownership, and cleanup; select them for consumers, resource tasks, scenarios, shared owners, and Spring Boot, with named lifecycle tasks and `--runtime` selection.
- Connect to externally managed emulators through `awsEmulator.external`, with explicit HTTPS endpoints, credentials, and CA files; preserve external lifecycle and state across consumers, provisioning, Spring Boot, and configuration-cache reuse.
- Declare the Kinesis base task caching policy explicitly for Gradle 9 plugin validation.
- Preserve lifecycle and image locks when operations in the same JVM contend, and add regression coverage for interrupted managed startup and cleanup.
- Add SES API v2 identity, template, configuration-set, and SNS event-destination provisioning; simple/raw email sends and message insights; Spring Boot setup discovery; and a Docker-tested example. Document the pinned emulator's template limitations and lack of SMTP forwarding to MailHog/Mailpit.
- Add Cognito User Pool and public app client tasks, confirmed user/group fixtures, runtime configuration exports, and a Spring Security example with signed JWT verification and lifecycle/configuration-cache tests. Document the pinned emulator's ES256 token compatibility requirement.
- Add Micronaut and Ktor examples using the core plugin, with framework-managed/HTTP SQS consumers, version compatibility checks, and real-emulator lifecycle and configuration-cache tests.
- Preserve selected-JDK and custom TLS trust roots alongside the emulator certificate in isolated consumer trust stores, including lazy JVM and environment options.

- Nest Spring Boot settings under `awsEmulator { springBoot { ... } }`, retaining `springBootEmulator` as a compatibility alias.
- Automatically provision registered AWS resource-creation tasks before Spring Boot local run/debug tasks, with inherited setup markers, matching runtime defaults, and optional exclusions.
- Add the separate `io.github.gregwhitaker.aws-emulator.spring-boot` companion plugin with local run/debug tasks, profile and JVM settings, explicit setup dependencies, and application-selected Spring Boot versions.
- Extend `awsEmulator.useWith` with lazy runtime selection and setup dependencies while preserving managed defaults for existing consumers.
- Include Spring Boot 3.5/4.1 compatibility tests, real-emulator lifecycle verification, a runnable example, and companion API documentation.
- Configure Gradle Plugin Portal publishing, publication metadata, CI validation, and a version-tagged release workflow.
- Add SNS topic creation, listing, deletion, publishing, and SQS subscription tasks for managed and development runtimes.
- Support standard/FIFO SNS topics, preserve existing queue policies and messages during subscription setup, and verify SNS-to-SQS delivery and configuration-cache reuse.
- Add `DynamoDbModule` with SDK v2 table creation, listing, deletion, secondary indexes, stream/encryption settings, and build-classpath initializers.
- Wait for DynamoDB table readiness before initialization and consuming tests, and wait for deletion to complete.
- Include a Kotlin DSL DynamoDB example with a `buildSrc` initializer and configuration-cache verification.
- Add a `docs` subproject with a static user guide, generated Java API reference, link checks, and ZIP packaging.
- Use Kotlin DSL for the repository's build and settings scripts.
- Move the published plugin into `plugin/` with separate `test`, `functional`, and `integration` source sets.
- Replace the lifecycle operation dispatcher with dedicated public task types.
- Organize tasks by feature using `EmulatorModule`, `AwsModule`, `S3Module`, and `SqsModule`, with separate lifecycle, S3, and SQS task groups.
- Separate Docker commands, image preparation, workspace ownership, and runtime coordination.
- Add `CreateS3BucketTask` and `CreateSqsQueueTask` using AWS SDK for Java v2.
- Port the remaining LocalStack S3/SQS task types into their feature modules: bulk creation, bucket listing/purging/deletion, queue listing/purging, DLQ creation, and message publishing.
- Register `listS3Buckets`, `purgeS3Buckets`, `deleteS3Buckets`, `listSqsQueues`, `purgeSqsQueues`, and `publishSqs` for the development runtime, with CLI selectors and a `--mode` override.
- Remove S3 object versions and delete markers during purge or forced deletion, including suspended versioning; check each batch for failed deletions.
- Support standard and FIFO DLQs and publishing UTF-8 message fixtures from files, directories, and tar.gz/tgz archives.
- Support standard and FIFO SQS queues, queue attributes, and regional S3 bucket creation.
- Reuse existing resources without deleting data; fail on conflicting SQS attributes.
- Share provisioned managed resources with opted-in tests and Java execution, including configuration-cache reuse and failure cleanup.
- Allow resource tasks to target the persistent development instance with `EmulatorMode.DEVELOPMENT`.
- Use explicit local SDK connections, generated credentials, and per-client TLS trust.
- Extend the example to cover S3 objects and standard/FIFO SQS messages.

## 0.1.0 — Unreleased

- Add Docker preparation, start, stop, restart, status, and log tasks for AWS Emulator.
- Pin AWS Emulator 1.0.322 and support AMD64 and ARM64 image builds.
- Add opt-in managed `Test` and `JavaExec` integration using a shared Gradle build service.
- Configure generated credentials, HTTPS trust, endpoint, and region for consuming JVMs.
- Isolate development and managed instances, and clean up managed instances after failures.
- Support configuration-cache reuse and separate runtimes for parallel projects.
- Include a signed AWS SDK for Java v2 example and Docker integration tests.
