# Examples

Standalone Kotlin DSL builds demonstrating the AWS Emulator Gradle plugin with AWS SDK for Java v2 and JUnit 5. Examples use this checkout through composite builds, so publishing is unnecessary. The standalone Testcontainers example consumes the Java library without applying the emulator plugin.

| Example | Demonstrates |
| --- | --- |
| [Standalone Testcontainers](testcontainers/README.md) | JUnit-owned AWS Emulator, explicit SDK TLS and credentials, and S3 requests without the Gradle emulator plugin. |
| [Basic](basic/README.md) | S3 buckets, standard and FIFO SQS queues, a dead-letter queue, message fixtures, and signed STS requests. |
| [Named runtimes](named-runtimes/README.md) | Independent test suites and development scenarios within one project, reusable resources, and an optional external connection. |
| [Existing emulator](external/README.md) | Explicit endpoint, credentials, and CA files; SQS/Kinesis provisioning and tests without managing the emulator's lifecycle. |
| [External processes](processes/README.md) | Python/Node `Exec` consumers, AWS CLI, shell/PowerShell connection exports, and IDE launch environments. |
| [Multi-project](multi-project/README.md) | Explicit runtime ownership, shared SQS provisioning, and an order exchange between two modules with managed tests or persistent development runs. |
| [DynamoDB](dynamodb/README.md) | Table creation, a global secondary index, stream settings, and a Java initializer supplied by `buildSrc`. |
| [Additional services](services/README.md) | SSM Parameter Store, Secrets Manager, EventBridge delivery to SQS, Kinesis, KMS, and CloudWatch Logs. |
| [Cognito](cognito/README.md) | User Pools, public app clients, user/group fixtures, runtime ID exports, and signed JWT authorization with Spring Security. |
| [SES](ses/README.md) | Email identities, stored templates, simple/raw sends, message insights, and simulated send/bounce events through SNS and SQS. |
| [Data fixtures and scenarios](scenarios/README.md) | S3 file uploads and downloads, DynamoDB JSON seeding and clearing, and named development scenarios with reset/reseed. |
| [Spring Boot](spring-boot/README.md) | Companion plugin, local run/debug tasks, Spring profiles, SQS provisioning, and managed or persistent emulator connections. |
| [Micronaut](micronaut/README.md) | Micronaut-managed SQS client injection, `@MicronautTest`, and managed tests or persistent application runs through the core plugin. |
| [Ktor](ktor/README.md) | Kotlin HTTP/SQS route, Ktor `testApplication`, and managed tests or persistent application runs through the core plugin. |
| [Spring Cloud AWS](spring-cloud-aws/README.md) | Reusable resources for tests, Boot runs, and scenarios; `@SqsListener`, async clients, `SqsTemplate`, and SSM/Secrets Manager configuration imports. |

Each project README includes run commands, source links, and instructions for managed tests and persistent development instances. Start with the basic example to see the plugin's provisioning and test integration.

## Getting started

Use Java 17 or later, a running local Docker engine configured for Linux containers, and the repository's Gradle wrapper. The `docker` command must be available for examples that own their runtime; the existing-emulator example only needs connection details. No AWS account or AWS CLI is required.

Run the documented commands from the **repository root**. The examples are independent builds selected with `-p examples/<example>`; they are not subprojects of the root build. The first run downloads dependencies and builds the emulator image, which later runs reuse.

See the [main README](../README.md) for plugin configuration and the [documentation README](../docs/README.md) for instructions to generate the HTML guide and API reference.
