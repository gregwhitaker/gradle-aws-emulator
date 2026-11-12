# Examples

Standalone Kotlin DSL builds demonstrating the AWS Emulator Gradle plugin with AWS SDK for Java v2 and JUnit 5. Each example uses the plugin from this checkout, so publishing or installing the plugin is unnecessary.

## Prerequisites

- Java 17 or later.
- A running local Docker engine configured for Linux containers, with the `docker` command available.
- The repository's Gradle wrapper, which supplies the required Gradle version.

The first run downloads dependencies and builds the emulator image; later runs reuse the image. No AWS account or AWS CLI is required.

## Run an example

Run these commands from the **repository root**. The examples are independent builds, so select one with `-p`; they are not subprojects of the root build.

| Example | Demonstrates | Entry task |
| --- | --- | --- |
| [basic](basic/) | S3 buckets, standard and FIFO SQS queues, a FIFO dead-letter queue, message fixtures, and signed STS requests. | `integrationTest` |
| [dynamodb](dynamodb/) | A DynamoDB table with a global secondary index, stream settings, and a Java initializer supplied by `buildSrc`. | `integrationTest` |
| [services](services/) | SSM Parameter Store, Secrets Manager, EventBridge delivery to SQS, Kinesis, KMS encryption/decryption, and CloudWatch Logs. | `verifyServices` |

```sh
./gradlew -p examples/basic integrationTest --configuration-cache
./gradlew -p examples/dynamodb integrationTest --configuration-cache
./gradlew -p examples/services verifyServices --configuration-cache
```

Run any command a second time to exercise configuration-cache reuse. Each invocation still provisions fresh emulator resources and runs the integration tests. Test reports are written to `examples/<example>/build/reports/tests/integrationTest/index.html`.

To inspect the available tasks without starting Docker:

```sh
./gradlew -p examples/basic tasks --all
```

### Basic

The [build script](basic/build.gradle.kts) creates buckets and queues, configures the FIFO queue's dead-letter policy, and publishes [message fixtures](basic/src/integration/messages/events.jsonl) before the tests run. The [integration tests](basic/src/integration/java/example/EmulatorIntegrationTest.java) verify S3 object round trips, SQS message delivery, the dead-letter policy, and STS identity over authenticated HTTPS.

### DynamoDB

The [build script](dynamodb/build.gradle.kts) creates `example-products` with an `id` partition key, a `by-category` global secondary index, and streams enabled. [ProductTableInitializer](dynamodb/buildSrc/src/main/java/example/buildsrc/dynamodb/ProductTableInitializer.java) seeds a product using the plugin's DynamoDB client. The [integration tests](dynamodb/src/integration/java/example/DynamoDbIntegrationTest.java) read that product and query the index.

This example resolves the checkout through [buildSrc/settings.gradle.kts](dynamodb/buildSrc/settings.gradle.kts) and declares the plugin and SDK together in [buildSrc/build.gradle.kts](dynamodb/buildSrc/build.gradle.kts), keeping the initializer and plugin on the same SDK classpath. The other examples include the plugin through `pluginManagement` in their settings files.

### Additional services

The [build script](services/build.gradle.kts) seeds parameters and secrets from [fixtures](services/fixtures/), connects an EventBridge rule to SQS, publishes Kinesis records, and encrypts/decrypts a local file. The [integration test](services/src/integration/java/example/ServicesIntegrationTest.java) reads the seeded data, consumes events and records, checks the decrypted file, and writes an application log event.

`verifyServices` runs that test and then retrieves and filters its log events. Generated files are under `examples/services/build/`:

- `fixtures/ciphertext.bin` and `fixtures/decrypted.txt` contain the KMS file round trip.
- `logs/events.json` and `logs/filtered.json` contain the retrieved CloudWatch log events.

## Managed lifecycle and failure cleanup

The test commands use a managed emulator with an automatically selected port. The plugin supplies the test JVM with the endpoint, region, credentials, and TLS trust. It removes the managed container and connection files when its consumers finish, including after an ordinary test failure.

The basic and services examples support intentional test failures to demonstrate cleanup:

```sh
./gradlew -p examples/basic integrationTest -PfailExample=true
./gradlew -p examples/services verifyServices -PfailExample=true
```

These commands are expected to fail. Omit `-PfailExample=true` for normal runs.

## Persistent development instances

Development instances remain running between Gradle invocations. They are separate from the managed instances used by the tests, and their resources are discarded when the container is stopped or restarted.

To create and inspect the DynamoDB example table:

```sh
./gradlew -p examples/dynamodb createProducts --mode=DEVELOPMENT
./gradlew -p examples/dynamodb listDynamoDbTables
./gradlew -p examples/dynamodb stopAwsEmulator
```

To provision and inspect the additional services:

```sh
./gradlew -p examples/services provisionServices -Pdevelopment=true
./gradlew -p examples/services listSsmParameters listSecrets listEventBuses listKinesisStreams listKmsKeys listLogGroups
./gradlew -p examples/services stopAwsEmulator
```

`-Pdevelopment=true` is specific to the services example's resource tasks. Run `verifyServices` in its default managed mode so provisioning and tests share an instance. When updating existing parameter or secret fixtures in a development instance, add `-Poverwrite=true` to the provisioning command to replace their values.

Use `statusAwsEmulator` or `logsAwsEmulator` with the same `-p examples/<example>` argument to inspect a development instance, and run `stopAwsEmulator` when finished.

See the [main README](../README.md) for plugin configuration and lifecycle details, the [DynamoDB guide](../docs/src/docs/asciidoc/dynamodb.adoc) for table and initializer options, and the [services guide](../docs/src/docs/asciidoc/services.adoc) for task properties and fixture formats. The [documentation README](../docs/README.md) explains how to generate the HTML guide and API reference.
