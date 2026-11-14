# Basic example

Demonstrates provisioning S3 buckets and SQS queues before JUnit 5 integration tests, then accessing them with AWS SDK for Java v2 over authenticated HTTPS.

Requires Java 17 or later, a running local Docker engine configured for Linux containers, and the repository's Gradle wrapper. No AWS account or AWS CLI is required. See the [examples index](../README.md) for shared setup.

## Run

From the **repository root**:

```sh
./gradlew -p examples/basic integrationTest --configuration-cache
```

Run the same command again to exercise configuration-cache reuse. Each run provisions fresh resources and executes the integration tests. Open `examples/basic/build/reports/tests/integrationTest/index.html` for the test report.

The plugin starts a managed emulator on an automatically selected port and supplies the test JVM with the endpoint, region, credentials, and TLS trust. It removes the managed container and connection files after its consumers finish, including after an ordinary test failure.

TLS trust includes the selected JDK's default roots and the emulator certificate. To add a custom JKS or PKCS12 store, set `EXAMPLE_TRUST_STORE_PASSWORD` when the store needs a password and run:

```sh
./gradlew -p examples/basic integrationTest --configuration-cache -PexampleTrustStore=/absolute/path/company.jks -PexampleTrustStoreType=JKS
```

The source store is read at execution time and remains unchanged. Its certificates are added to the consumer's trust; private keys are not copied. The generated combined store is removed with the managed instance.

## What it demonstrates

The [build script](build.gradle.kts) wires these provisioning tasks into `integrationTest`:

| Task | Resources or fixtures |
| --- | --- |
| `createExampleBuckets` | The `example-test-bucket` and `example-uploads` S3 buckets. |
| `createExampleQueue` | A standard SQS queue with a 45-second visibility timeout. |
| `createExampleFifoQueue` | The `example-events.fifo` queue and its FIFO dead-letter queue, with a maximum receive count of three. |
| `createFixtureQueues` | Two standard queues for fixture publishing. |
| `publishFixtures` | Publishes the [JSON Lines fixtures](src/integration/messages/events.jsonl) to `example-seeded-queue`. |

The [integration tests](src/integration/java/example/EmulatorIntegrationTest.java) verify S3 object round trips, standard and FIFO SQS message delivery, published fixtures, the dead-letter policy, and STS identity. The build deliberately supplies invalid inherited AWS settings to show that `useWith` supplies the emulator connection to the test JVM.

The [settings file](settings.gradle.kts) includes the plugin from this checkout through `pluginManagement`, so no plugin publication is needed.

## Failure cleanup

To deliberately fail a test and demonstrate managed cleanup:

```sh
./gradlew -p examples/basic integrationTest -PfailExample=true
```

This command is expected to fail; the managed container is still removed. Omit `-PfailExample=true` for normal runs.

## Persistent development instance

To create buckets and a standard queue that remain available between Gradle invocations:

```sh
./gradlew -p examples/basic createExampleBuckets --mode=DEVELOPMENT
./gradlew -p examples/basic createExampleQueue --mode=DEVELOPMENT
./gradlew -p examples/basic listS3Buckets listSqsQueues
./gradlew -p examples/basic statusAwsEmulator
./gradlew -p examples/basic reportAwsEmulator
./gradlew -p examples/basic stopAwsEmulator
```

The development instance is separate from the managed test instance. Stopping or restarting it discards its resources. Run `stopAwsEmulator` when finished.

## Resource reports

The development command above writes `index.html` and `report.json` under `examples/basic/build/reports/aws-emulator/default/development/reportAwsEmulator/`. It inspects existing resources without starting an emulator or changing resource state.

To capture the managed instance after integration tests, including ordinary test failures:

```sh
./gradlew -p examples/basic integrationTest -PemulatorReport --configuration-cache
```

The optional `reportIntegrationEmulator` finalizer writes both formats under `examples/basic/build/reports/aws-emulator/default/managed/reportIntegrationEmulator/` before cleanup. Repeat the command to check configuration-cache reuse; add `-PfailExample=true` to exercise failure capture. Reports cover S3, SQS, SNS, DynamoDB, and CloudFormation; this example provisions S3 and SQS, so the remaining sections are empty. See the [report guide](../../docs/src/docs/asciidoc/reports.adoc) for fields and collection errors.

See the [S3 guide](../../docs/src/docs/asciidoc/s3.adoc) and [SQS guide](../../docs/src/docs/asciidoc/sqs.adoc) for their task references. The [main README](../../README.md) covers lifecycle commands and SDK connection details.
