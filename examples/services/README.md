# Additional services example

Demonstrates provisioning SSM Parameter Store, Secrets Manager, EventBridge, Kinesis Data Streams, KMS, and CloudWatch Logs, then using them with AWS SDK for Java v2 in a JUnit 5 integration test. An SQS queue receives events routed by EventBridge.

Requires Java 17 or later, a running local Docker engine configured for Linux containers, and the repository's Gradle wrapper. No AWS account or AWS CLI is required. See the [examples index](../README.md) for shared setup.

## Run

From the **repository root**:

```sh
./gradlew -p examples/services verifyServices --configuration-cache
```

Run the same command again to exercise configuration-cache reuse. Each run provisions fresh resources and executes the integration test. `verifyServices` also retrieves and filters the log events written by the test. Open `examples/services/build/reports/tests/integrationTest/index.html` for the test report.

The plugin starts a managed emulator on an automatically selected port and supplies the test JVM with the endpoint, region, credentials, and TLS trust. It removes the managed container and connection files after its consumers finish, including after an ordinary test failure.

## What it demonstrates

The [build script](build.gradle.kts) provisions resources and fixtures before running the [integration test](src/integration/java/example/ServicesIntegrationTest.java):

| Service | Example behavior |
| --- | --- |
| SSM Parameter Store | Seeds string, string-list, and encrypted parameters from [parameters.json](fixtures/parameters.json), then reads their values. |
| Secrets Manager | Seeds a database secret from [secrets.json](fixtures/secrets.json), then reads it. |
| EventBridge and SQS | Creates an event bus, rule, and queue target; publishes [events.json](fixtures/events.json); verifies that the matching event reaches SQS. |
| Kinesis Data Streams | Creates a stream, publishes [records.json](fixtures/records.json), and consumes the records. |
| KMS | Creates a key with alias `alias/example-services`, then encrypts and decrypts [plaintext.txt](fixtures/plaintext.txt) with an encryption context. |
| CloudWatch Logs | Creates a log group and stream, writes an application log event, then retrieves and filters events. |

The [settings file](settings.gradle.kts) includes the plugin from this checkout through `pluginManagement`, so no plugin publication is needed.

### Entry tasks and output

| Task | Behavior |
| --- | --- |
| `provisionServices` | Creates the resources and seeds parameters and secrets. |
| `integrationTest` | Provisions resources, publishes event and stream fixtures, performs the KMS file round trip, and runs the SDK assertions. |
| `verifyServices` | Runs `integrationTest`, then `filterFixtureLogs` and `getFixtureLogs`. |

Generated files are under `examples/services/build/`:

- `fixtures/ciphertext.bin` and `fixtures/decrypted.txt` contain the KMS file round trip.
- `logs/events.json` and `logs/filtered.json` contain the retrieved CloudWatch log events.

## Failure cleanup

To deliberately fail the test and demonstrate managed cleanup:

```sh
./gradlew -p examples/services verifyServices -PfailExample=true
```

This command is expected to fail; the managed container is still removed. Omit `-PfailExample=true` for normal runs.

## Persistent development instance

To provision resources that remain available between Gradle invocations:

```sh
./gradlew -p examples/services provisionServices -Pdevelopment=true
./gradlew -p examples/services listSsmParameters listSecrets listEventBuses listKinesisStreams listKmsKeys listLogGroups
./gradlew -p examples/services statusAwsEmulator
./gradlew -p examples/services stopAwsEmulator
```

`-Pdevelopment=true` is specific to this example's resource tasks. Run `verifyServices` in its default managed mode so provisioning and tests share an instance. `provisionServices` creates the resources and seeds configuration and secrets; event publishing, stream record publishing, and application log writes happen as part of the test workflow.

When updating existing parameter or secret fixtures in a development instance, allow their values to be replaced with:

```sh
./gradlew -p examples/services provisionServices -Pdevelopment=true -Poverwrite=true
```

Development instances are separate from managed test instances. Stopping or restarting an instance discards its resources; run `stopAwsEmulator` when finished.

See the [services guide](../../docs/src/docs/asciidoc/services.adoc) for task properties, fixture formats, and overwrite behavior, or the [main README](../../README.md) for general plugin configuration.
