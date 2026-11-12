# Changelog

## 0.2.0 — Unreleased

- Add a `docs` subproject with a static user guide, generated Java API reference, link checks, and ZIP packaging.
- Use Kotlin DSL for the repository's build and settings scripts.
- Move the published plugin into `plugin/` with separate `test`, `functional`, and `integration` source sets.
- Replace the lifecycle operation dispatcher with dedicated public task types.
- Organize tasks by feature using `EmulatorModule`, `AwsModule`, `S3Module`, and `SqsModule`, with separate lifecycle, S3, and SQS task groups.
- Separate Docker commands, image preparation, workspace ownership, and runtime coordination.
- Add `CreateS3BucketTask` and `CreateSqsQueueTask` using AWS SDK for Java v2.
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
