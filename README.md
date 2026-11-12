# AWS Emulator Gradle Plugin

Gradle lifecycle tasks, AWS resource management, and opt-in JVM test integration for [AWS Emulator](https://github.com/aws/aws-emulator).

Plugin ID: **`io.github.gregwhitaker.aws-emulator`**. Version **0.2.0** is implemented in this repository; it has not been published to the Gradle Plugin Portal.

Requires Java 17+, Gradle 8.14+, and a local Docker engine running Linux containers. The plugin builds an image from AWS's pinned `1.0.322` Linux package on first use. AMD64 and ARM64 are supported; subsequent runs reuse the image. No AWS account or AWS CLI is needed.

## Documentation site

Generate the user guide and Java API reference locally:

```sh
./gradlew :docs:site
```

Open `docs/build/site/index.html` in a browser. `./gradlew :docs:check` checks local links and assets; `./gradlew :docs:siteZip` creates a verified static-site archive in `docs/build/distributions/`. Site generation requires Java and Gradle, with no Docker or separate frontend toolchain. See [docs/README.md](docs/README.md) for authoring and hosting instructions.

The [GitHub Pages workflow](.github/workflows/pages.yml) deploys the verified guide and API reference on pushes to `main` or a manual run from `main`. Enable **GitHub Actions** as the repository's Pages source using the [deployment setup instructions](docs/README.md#github-pages).

## Try the included example

From this repository:

```sh
./gradlew -p examples/basic integrationTest --configuration-cache
```

The Kotlin DSL example includes this plugin as a composite build. It provisions S3 buckets, standard/FIFO SQS queues and a FIFO DLQ, publishes message fixtures, verifies object and message round trips plus a signed STS request using AWS SDK for Java v2 over HTTPS, and removes the managed container and its connection files afterwards. Run the same command again to exercise configuration-cache reuse.

To demonstrate cleanup after a failed test:

```sh
./gradlew -p examples/basic integrationTest -PfailExample=true
```

That command is expected to fail its test; its managed container is still removed.

## Additional AWS services

Typed resource tasks now cover **SSM Parameter Store, Secrets Manager, EventBridge, Kinesis Data Streams, KMS, and CloudWatch Logs**. They support managed tests and persistent development instances using the same local SDK connection as the existing S3/SQS tasks.

Run the complete example twice to verify SDK round trips and configuration-cache reuse:

```sh
./gradlew -p examples/services verifyServices --configuration-cache
./gradlew -p examples/services verifyServices --configuration-cache
```

The example seeds configuration and secrets from JSON, routes EventBridge events into SQS, publishes/consumes stream records, encrypts/decrypts a file, and retrieves application logs. Use `-PfailExample=true` to exercise cleanup after a failed test. For persistent resources, run `provisionServices -Pdevelopment=true`, then `stopAwsEmulator` when finished.

See [the service task guide](docs/src/docs/asciidoc/services.adoc) for the complete task/property reference, Kotlin/Groovy DSL, fixture schemas, overwrite rules, deletion semantics, and supported limits. The generated guide is available at `docs/build/site/services.html` after `./gradlew :docs:site`.

## Apply from a checkout

Until publication, include this repository in your application's plugin management.

Kotlin `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("../gradle-aws-emulator")
}
```

Kotlin `build.gradle.kts`:

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

Groovy `settings.gradle`:

```groovy
pluginManagement {
    includeBuild('../gradle-aws-emulator')
}
```

Groovy `build.gradle`:

```groovy
plugins {
    id 'java'
    id 'io.github.gregwhitaker.aws-emulator'
}

awsEmulator {
    port.set(0)
    useWith(tasks.named('test'))
}
```

To opt in only integration tests, pass the provider for your existing `integrationTest` task instead. Applying the plugin alone does not start Docker or change test tasks.

## Lifecycle tasks

| Task | Behavior |
| --- | --- |
| `prepareAwsEmulator` | Build the pinned image, or locate/pull an image override. |
| `startAwsEmulator` | Start the project's development instance and wait for HTTPS readiness. Reuse it if its configuration matches. |
| `stopAwsEmulator` | Gracefully stop and remove the project's development container and generated connection files. Safe to repeat. |
| `restartAwsEmulator` | Stop and start the development instance with the current configuration and empty state. |
| `statusAwsEmulator` | Report the development container's state, health, and endpoint. |
| `logsAwsEmulator` | Print the last 100 lines from the development container. |

For example:

```sh
./gradlew -p examples/basic startAwsEmulator -PawsEmulator.port=9297
./gradlew -p examples/basic statusAwsEmulator
./gradlew -p examples/basic logsAwsEmulator
./gradlew -p examples/basic stopAwsEmulator
```

These tasks operate on a persistent **development** instance. It survives the end of a Gradle invocation. Changed settings require `restartAwsEmulator`; an unrelated container with the same name is never adopted or stopped.

AWS Emulator keeps resources in memory. Stop and restart discard those resources. There is no state-persistence or reset API in this release of the emulator.

## Managed tests and Java execution

`awsEmulator.useWith(taskProvider)` supports Gradle `Test` and `JavaExec` tasks, including subclasses. A build service starts one fresh **managed** instance per participating project and shares it among that project's opted-in tasks. Managed instances have separate container names and directories from development instances.

The service removes its managed container and files after its consumers finish, including ordinary task/test failures. Skipped and `NO-SOURCE` consumers do not start an emulator themselves. Explicit provisioning dependencies still execute when those consumers are skipped or have no sources. Opted-in tasks always execute when they have work: up-to-date checks and build-cache reuse are disabled because the emulator starts with fresh external state.

For Java application execution:

```kotlin
awsEmulator {
    useWith(tasks.named<JavaExec>("run"))
}
```

This only configures an existing task. Spring Boot-specific task creation is planned for a later release.

Tasks sharing an instance also share emulated resources; use distinct resource names when tests run concurrently. Each project gets its own instance. Set `port` to `0` for parallel projects, concurrent builds, or simultaneous managed and development instances. The plugin selects an available host port and passes that same nonzero port to both Docker and the emulator. A competing process can still claim a selected port before Docker binds it; the plugin reports the failure and cleans up rather than attaching to that process.

Graceful cleanup requires the Gradle process and Docker daemon to remain available. A hard kill or daemon failure can leave a container behind. Locate plugin-owned containers with:

```sh
docker ps -a --filter label=io.github.gregwhitaker.aws-emulator.owner
```

Remove only the specific stale container you identify. The plugin does not prune other containers, images, or AWS configuration.

## Provision S3 buckets and SQS queues

Register typed tasks for the resources your application needs, then make your test task depend on them. Each task starts the emulator on demand and waits for readiness. Custom resource tasks default to the same managed instance used by `awsEmulator.useWith` in that project.

Kotlin DSL:

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
    attributes.put("VisibilityTimeout", "45")
}
val test = tasks.named<Test>("test") {
    dependsOn(createBucket, createQueue)
}
awsEmulator {
    port.set(0)
    useWith(test)
}
```

Groovy DSL:

```groovy
import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketTask
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask

plugins {
    id 'java'
    id 'io.github.gregwhitaker.aws-emulator'
}

def createBucket = tasks.register('createTestBucket', CreateS3BucketTask) {
    bucketName.set('application-test-data')
}
def createQueue = tasks.register('createTestQueue', CreateSqsQueueTask) {
    queueName.set('application-test-events')
    attributes.put('VisibilityTimeout', '45')
}
def tests = tasks.named('test') {
    dependsOn(createBucket, createQueue)
}
awsEmulator {
    port.set(0)
    useWith(tests)
}
```

The plugin declares its own AWS SDK for Java v2 dependencies for provisioning tasks. Your application declares its own SDK dependencies, as shown in `examples/basic`.

| Task type | Required properties | Optional properties |
| --- | --- | --- |
| `CreateS3BucketTask` | `bucketName` | `mode`, `apiCallTimeoutSeconds` |
| `CreateS3BucketsTask` | `buckets` (list) | `mode`, `apiCallTimeoutSeconds` |
| `ListS3BucketsTask` | — | `mode`, `apiCallTimeoutSeconds` |
| `PurgeS3BucketsTask` | `buckets` (list) | `mode`, `apiCallTimeoutSeconds` |
| `DeleteS3BucketsTask` | `buckets` (list) | `force` (default `false`), `mode`, `apiCallTimeoutSeconds` |
| `CreateSqsQueueTask` | `queueName` | `attributes` (map of AWS attribute names to string values), `mode`, `apiCallTimeoutSeconds` |
| `CreateSqsQueuesTask` | `queueNames` (list) | `queueAttributes` (map), `mode`, `apiCallTimeoutSeconds` |
| `CreateSqsQueueWithDlqTask` | `queueName` | `queueAttributes`, `deadletterQueueName`, `deadletterQueueAttributes`, `maxReceiveCount` (default `1`), `mode`, `apiCallTimeoutSeconds` |
| `ListSqsQueuesTask` | — | `prefix`, `mode`, `apiCallTimeoutSeconds` |
| `PurgeSqsQueuesTask` | `queueNames` (list) | `mode`, `apiCallTimeoutSeconds` |
| `PublishSqsTask` | `queueNames` (list), `messages` (file collection) | `messageGroupId` (required for FIFO), `mode`, `apiCallTimeoutSeconds` |

All properties are lazy Gradle properties. Custom task instances default to `EmulatorMode.MANAGED`; `apiCallTimeoutSeconds` defaults to `30` per SDK operation, including retries. These tasks always execute and are never restored from the build cache because they operate on live emulator state. A managed provisioning task invoked alone creates resources that are removed when the build finishes.

`CreateS3BucketTask` and `CreateS3BucketsTask` create general-purpose buckets in `awsEmulator.region`. They omit the location constraint for `us-east-1` and set it for other regions. Existing accessible buckets are reused without another `CreateBucket` call, preserving their objects and configuration. A region mismatch reported by `HeadBucket` fails the task. Directory buckets, access points, bucket policies, and object seeding are outside 0.2's scope.

`CreateSqsQueueTask` and `CreateSqsQueuesTask` support standard and FIFO queues. Existing queues are reused when explicitly requested attribute values match; a mismatch fails without changing or purging the queue. Unspecified attributes retain their existing values. Attribute comparison uses the returned string values, including JSON-valued attributes. AWS service errors remain build failures.

FIFO queues need both a `.fifo` suffix and `FifoQueue = true`:

```kotlin
val createFifo = tasks.register<CreateSqsQueueTask>("createTestFifo") {
    queueName.set("application-events.fifo")
    attributes.put("FifoQueue", "true")
    attributes.put("ContentBasedDeduplication", "true")
}
```

To provision the persistent development instance, select `DEVELOPMENT` on each resource task:

```kotlin
import io.github.gregwhitaker.awsemulator.model.EmulatorMode
import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketTask

val developmentBucket = tasks.register<CreateS3BucketTask>("createDevelopmentBucket") {
    mode.set(EmulatorMode.DEVELOPMENT)
    bucketName.set("application-development-data")
}
```

Running `createDevelopmentBucket` starts or reuses the development instance and leaves it running. `stopAwsEmulator` removes it; `restartAwsEmulator` discards its resources. Test setup must use `MANAGED` to share the runtime selected by `useWith`.

Provisioning clients use explicit emulator endpoints, regions, and generated credentials, plus per-client TLS trust. They bypass inherited proxy settings and do not change the Gradle daemon's credentials or global SSL settings. No AWS CLI is required.

## S3 and SQS module tasks

The modules register these convenience tasks for the persistent **development** instance:

| Group | Registered tasks |
| --- | --- |
| `AWS Emulator - S3` | `listS3Buckets`, `purgeS3Buckets`, `deleteS3Buckets` |
| `AWS Emulator - SQS` | `listSqsQueues`, `purgeSqsQueues`, `publishSqs` |

```sh
./gradlew listS3Buckets
./gradlew listSqsQueues --prefix=application-
./gradlew publishSqs --queueNames=application-events --message=src/integration/messages
./gradlew purgeSqsQueues --queueNames=application-events
./gradlew purgeS3Buckets --buckets=application-data,application-uploads
./gradlew deleteS3Buckets --buckets=application-data --force
```

These commands start or reuse the development emulator. Use `--mode=MANAGED` to select the managed runtime in a build that also provisions or consumes those resources. Custom instances of the same types still default to `MANAGED`.

S3 listing follows all pages. Purging removes objects, historical versions, and delete markers, including when versioning is suspended, while keeping the bucket. Deletion requires an empty bucket unless `force` is `true`; deleting an absent bucket succeeds. Purge and delete require explicit bucket names.

SQS listing includes queue names, URLs, and approximate available message counts. Purge requires explicit queue names and retains the queues. It requests deletion of available and in-flight messages; the service can take up to 60 seconds to complete the purge. SDK errors fail the task.

### Bulk creation and deadletter queues

```kotlin
import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketsTask
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueuesTask
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueWithDlqTask

val createBuckets = tasks.register<CreateS3BucketsTask>("createTestBuckets") {
    buckets.set(listOf("application-data", "application-uploads"))
}
val createQueues = tasks.register<CreateSqsQueuesTask>("createTestQueues") {
    queueNames.set(listOf("application-events", "application-notifications"))
    queueAttributes.put("VisibilityTimeout", "45")
}
val createOrders = tasks.register<CreateSqsQueueWithDlqTask>("createOrdersQueue") {
    queueName.set("application-orders.fifo")
    queueAttributes.put("FifoQueue", "true")
    maxReceiveCount.set(3)
}
```

Bulk creation uses the same reuse and attribute checks as the singular tasks. The DLQ task defaults to `<queue>-dlq`, inserting `-dlq` before `.fifo` for FIFO queues. It infers `FifoQueue=true` for a FIFO DLQ and requires matching queue types. Override `deadletterQueueName` or `deadletterQueueAttributes` as needed.

The DLQ task creates or reuses the DLQ, reads its actual ARN, then creates or updates the source queue's requested attributes and redrive policy. Existing messages are retained subject to the configured queue attributes. This task deliberately updates an existing source queue; the ordinary creation tasks only verify it. Configure the redrive policy through `deadletterQueueName` and `maxReceiveCount` rather than a `RedrivePolicy` entry.

### Publish message fixtures before tests

```kotlin
import io.github.gregwhitaker.awsemulator.aws.sqs.PublishSqsTask

val publishOrders = tasks.register<PublishSqsTask>("publishTestOrders") {
    dependsOn(createOrders)
    queueNames.add("application-orders.fifo")
    messages.from(layout.projectDirectory.dir("src/integration/messages"))
    messageGroupId.set("integration-tests")
}
tasks.named<Test>("test") {
    dependsOn(createBuckets, createQueues, publishOrders)
}
awsEmulator { useWith(tasks.named<Test>("test")) }
```

`messages` accepts UTF-8 files, directories traversed recursively, and `.tar.gz`/`.tgz` archives. Every nonempty line is one message sent to every selected queue; whitespace is preserved. Directory files are processed in path order, archive entries in archive order. Hidden entries and directory symlinks are skipped, and archives are read without extraction. Invalid input fails before publishing begins. Service failures stop publishing; earlier successful sends remain, so rerunning can send duplicates.

FIFO targets require `messageGroupId` (or `--messageGroupId` on `publishSqs`). Each delivery gets a unique deduplication ID so identical fixture lines remain separate messages. Publishing tasks always execute, including on configuration-cache reuse.

## SNS topics and SQS subscriptions

SNS tasks create standard/FIFO topics, subscribe SQS queues, list topics and subscriptions, publish messages, and delete topics.

```kotlin
import io.github.gregwhitaker.awsemulator.aws.sns.CreateSnsTopicWithSqsEndpointTask
import io.github.gregwhitaker.awsemulator.aws.sns.PublishSnsTask

val createNotifications = tasks.register<CreateSnsTopicWithSqsEndpointTask>("createNotifications") {
    topicName.set("application-notifications")
    subscriptionAttributes.put("RawMessageDelivery", "true")
}
val publishNotification = tasks.register<PublishSnsTask>("publishTestNotification") {
    dependsOn(createNotifications)
    topicName.set("application-notifications")
    message.set("""{"event":"created"}""")
}
val notificationTests = tasks.named<Test>("test") { dependsOn(publishNotification) }
awsEmulator { useWith(notificationTests) }
```

The subscriber queue defaults to `<topic>-subscriber`. The task preserves existing queue messages and policy statements, adds a topic-specific delivery grant, and reuses the subscription. Existing topics and queues must match explicitly requested attributes. Use `CreateSnsTopicTask` with `topicName` and optional `attributes` for a topic without a queue.

Four convenience tasks target the persistent development instance:

```sh
./gradlew listSnsTopics
./gradlew listSnsTopicSubscriptions --topicName=application-notifications
./gradlew publishSns --topicName=application-notifications --message='{"event":"created"}'
./gradlew deleteSnsTopic --topicName=application-notifications
```

Provision those development topics with `mode.set(EmulatorMode.DEVELOPMENT)` first. Topic deletion also removes subscriptions but retains their SQS queues. See the [SNS guide](docs/src/docs/asciidoc/sns.adoc) for all task properties, FIFO configuration, and Groovy examples.

## DynamoDB tables

`DynamoDbModule` groups table tasks under `AWS Emulator - DynamoDB` using AWS SDK for Java v2.

| Task type | Registration | Behavior |
| --- | --- | --- |
| `CreateDynamoDbTableTask` | Register a named task | Create or reuse a table, wait for `ACTIVE`, and optionally initialize a newly created table. |
| `ListDynamoDBTablesTask` | `listDynamoDbTables` | List every table, following all response pages. |
| `DeleteDynamoDbTableTask` | `deleteDynamoDbTable` | Delete the named table and its items; wait for deletion to complete. |

```kotlin
import io.github.gregwhitaker.awsemulator.aws.dynamodb.CreateDynamoDbTableTask
import software.amazon.awssdk.services.dynamodb.model.*

val createProducts = tasks.register<CreateDynamoDbTableTask>("createProducts") {
    tableName.set("catalog.products")
    keySchema.add(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
    attributeDefinitions.add(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
}
tasks.named<Test>("test") { dependsOn(createProducts) }
awsEmulator { useWith(tasks.named<Test>("test")) }
```

Creation supports `globalSecondaryIndexes`, `localSecondaryIndexes`, `provisionedThroughput` (100 read/write units by default), `sseSpecification` (`enabled=true` by default), and optional `streamSpecification`. These are lazy properties containing SDK v2 model objects. Each GSI requires its own throughput. The task validates the schema before starting Docker.

Existing tables are reused without changing their definitions or items and without rerunning initializers. Creation and deletion use `waitTimeoutSeconds` (default `60`); individual SDK calls use `apiCallTimeoutSeconds` (default `30`). Deleting a missing table fails, matching the LocalStack task.

The registered list/delete commands use the development emulator; custom tasks default to managed mode. For a development session:

```sh
./gradlew createProducts --mode=DEVELOPMENT
./gradlew listDynamoDbTables
./gradlew deleteDynamoDbTable --tableName=catalog.products
./gradlew stopAwsEmulator
```

Set `initializer` to the fully qualified name of a public build-classpath class with a `DynamoDbClient` constructor and a public instance `void run()` method. It receives the plugin's client after the new table is active. When using `buildSrc`, include the plugin and SDK together in its dependencies so they share SDK types.

The [DynamoDB guide](docs/src/docs/asciidoc/dynamodb.adoc) covers all properties and initializer setup. The [standalone example](examples/dynamodb/build.gradle.kts) uses a Kotlin build and a `buildSrc` initializer:

```sh
./gradlew -p examples/dynamodb integrationTest --configuration-cache
```

## Configuration

All properties use Gradle's lazy property API.

| Property | Default | Meaning |
| --- | --- | --- |
| `emulatorVersion` | `1.0.322` | Pinned emulator release used to build the image. |
| `image` | unset | Use this Docker image instead of building one; pull it if missing locally. It must have an AWS Emulator entrypoint binding `0.0.0.0` and accept the normal emulator CLI flags. |
| `platform` | `auto` | Detect the Docker engine architecture, or specify `linux/amd64` or `linux/arm64`. |
| `port` | `9297` | Host/container port; `0` asks the plugin to select one. Also configurable with `-PawsEmulator.port=...`. |
| `region` | `us-east-1` | Region supplied to consuming SDKs. |
| `telemetryEnabled` | `false` | Explicitly enable the emulator's AWS telemetry. |
| `dockerExecutable` | `docker` | Docker executable name or absolute path. |
| `memoryLimit` | `2g` | Container memory limit; swap is capped at the same total. |
| `startupTimeoutSeconds` | `60` | Readiness deadline after the container starts. |
| `commandTimeoutSeconds` | `60` | Timeout for each normal Docker command. |
| `buildTimeoutSeconds` | `600` | Timeout for an image build or pull. |
| `workingDirectory` | `.aws-emulator` in the project | Generated Dockerfiles, locks, and isolated runtime homes. |

Add `.aws-emulator/` to your application's `.gitignore`. The directory contains generated local credentials while the emulator runs. Image layers remain in Docker's cache after instances stop.

## Credentials, endpoints, and TLS

For each opted-in JVM, the plugin sets the endpoint, region, emulator credentials/config paths, and TLS trust store. It removes inherited AWS credential, profile, and service-endpoint environment overrides and corresponding configured JVM credential/endpoint properties. It disables EC2 metadata lookup and endpoint discovery. It does not change the Gradle daemon's credentials or global trust settings.

Connection files are generated at execution time, after readiness, and are not stored as configuration-cache values. The plugin writes a separate `host-config` with host paths because the emulator's own `config` contains container paths. Authentication remains enabled. Readiness checks validate both the generated certificate and the endpoint hostname.

The supplied Java trust store contains the emulator certificate only. A consuming JVM that must also reach other HTTPS servers needs a combined trust store; this release does not automatically merge trust roots. Configure that separately in your application if needed. Explicit SDK client endpoint/credential configuration, JVM argument providers, and JVM options injected through the environment can override the plugin's supplied settings; use the normal SDK providers as demonstrated by the example.

Use an AWS SDK version that supports external endpoint configuration. The included example verifies AWS SDK for Java v2 `2.39.6`. An SDK client that hardcodes an endpoint will continue to use it. For operations using host prefixes, Java SDK clients also need `SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION` in their client override configuration; the environment variable alone does not control this in Java. The endpoint uses `127.0.0.1` to support S3 path-style routing.

For a development instance on port 9297, host-side files live under:

```text
.aws-emulator/development/.aws/emulator/9297/
  emulator_credentials
  ca.pem
  truststore.p12
  host-config
```

The lifecycle tasks print the actual endpoint and directory, including when a port is selected automatically.

AWS references: [Docker installation](https://docs.aws.amazon.com/emulator/latest/userguide/getting-started-docker.html), [SDK configuration](https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html), [CLI and runtime files](https://docs.aws.amazon.com/emulator/latest/userguide/cli-reference.html), [versioned packages](https://docs.aws.amazon.com/emulator/latest/userguide/versioning.html).

## Repository and package layout

The repository uses Kotlin DSL build scripts. The root build coordinates the plugin and documentation subprojects. Only the plugin produces a published library artifact. The example is an independent composite build that consumes the plugin as an application would.

```text
build.gradle.kts                     root verification and wrapper configuration
settings.gradle.kts                  includes :plugin and :docs
gradle.properties                    shared plugin/documentation version
docs/                               static user guide, site assets, and link verification
  build.gradle.kts                   site generation, Java API inclusion, and ZIP packaging
  src/docs/asciidoc/                 guide pages and shared navigation
  src/site/                         browser assets
plugin/
  build.gradle.kts                   plugin dependencies, metadata, and publishing
  src/main/java/io/github/gregwhitaker/awsemulator/
    AwsEmulatorPlugin.java           loads the emulator and AWS modules
    emulator/
      EmulatorModule.java           registers the lifecycle task group and runtime
      AwsEmulatorExtension.java     lazy runtime settings and useWith integration
      AwsEmulatorService.java       shared runtime lifetime
      *AwsEmulatorTask.java         lifecycle task types
    aws/
      AwsModule.java                loads the AWS service modules
      AbstractAwsResourceTask.java  shared resource-task configuration
      s3/                           S3Module, S3 task types, and provisioning helpers
      sqs/                          SqsModule, SQS task types, and provisioning helpers
      sns/                          SnsModule, SNS tasks, and SQS subscription helpers
      dynamodb/                     DynamoDbModule, table tasks, and initializer support
      internal/                     execution-scoped SDK clients
    core/                           shared emulator task base
    model/                          public configuration types
    internal/
      docker/                       process execution, Docker commands, image builds
      runtime/                      runtime coordination, TLS, connections, owned files
  src/test/                          unit tests without Docker
  src/functional/                    Gradle TestKit DSL and configuration tests
  src/integration/                   real Docker and SDK integration tests
  src/testFixtures/                  shared TestKit runner setup
examples/basic/                      runnable Kotlin DSL consumer
```

Tasks are organized by feature, following the module pattern in `gradle-localstack`. `AwsEmulatorPlugin` loads `EmulatorModule` and `AwsModule`; `AwsModule` loads the AWS service modules. Each service package keeps its module, task types, and provisioning helpers together. Modules register or configure tasks lazily when the plugin is applied. The build service owns the runtime lifetime, and everything under `internal` is an implementation detail.

| Module | Gradle task group | Responsibilities |
| --- | --- | --- |
| `EmulatorModule` | `AWS Emulator` | Runtime configuration and the six lifecycle tasks. |
| `S3Module` | `AWS Emulator - S3` | Bucket creation, listing, purging, and deletion. |
| `SqsModule` | `AWS Emulator - SQS` | Queue and DLQ creation, listing, purging, and message publishing. |
| `SnsModule` | `AWS Emulator - SNS` | Topic creation, listing, deletion, SQS subscriptions, and message publishing. |
| `DynamoDbModule` | `AWS Emulator - DynamoDB` | Table creation, initialization, listing, and deletion. |

Register named resource tasks in your build; their service module supplies the group and default description. Imports use `io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketTask` and `io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask`.

Separate test source sets follow [Gradle's plugin testing guidance](https://docs.gradle.org/current/userguide/testing_gradle_plugins.html#sec:organizing_test_source_code). Functional and integration tests mirror the production packages: service-specific tests live under `aws.<service>`, scenarios spanning AWS services under `aws`, emulator lifecycle integration tests under `emulator`, and plugin-wide tests in the root plugin package. The feature modules are packaged together in the plugin artifact.

## Build and verify

```sh
./gradlew build
./gradlew :plugin:integrationTest
```

`build` runs unit tests, Gradle TestKit tests that require no Docker, plugin validation, and documentation generation/link checks/packaging. `:plugin:integrationTest` (also available as `dockerTest`) requires a running local Docker daemon and downloads/builds the emulator image if necessary. It verifies signed HTTPS SDK requests, sharing between tasks, configuration-cache reuse, failure cleanup, all lifecycle tasks, parallel projects, S3/SQS provisioning, repeated setup without data loss, queue attribute conflicts, versioned bucket cleanup, DLQ updates, and message publishing from files and archives. Docker tests deliberately fail a nested fixture build to check cleanup; the enclosing test must pass.

To run TestKit consumers against another Gradle version:

```sh
./gradlew :plugin:functionalTest :plugin:integrationTest -PtestGradleVersion=9.6.1
```

`-PtestGradleInstallation=/path/to/gradle` can instead use an existing installation. The CI matrix is configured for Gradle 8.14.3 and 9.6.1, Java 17 and 21, and real Docker execution on Linux AMD64 and ARM64. Windows runs the Docker-free suite; Docker integration on Windows has not been verified.

`java-gradle-plugin`, `maven-publish`, and `com.gradle.plugin-publish` generate the plugin artifact and marker publication. Build artifacts are in `plugin/build/libs/`; reports are in `plugin/build/reports/tests/`. The implementation coordinate remains `io.github.gregwhitaker:gradle-aws-emulator:0.2.0` and the plugin ID remains `io.github.gregwhitaker.aws-emulator`. Use the composite build above for local development. CI builds both publications in a temporary Maven repository; the release workflow publishes version tags.

## Publish to the Gradle Plugin Portal

The release version comes from `pluginVersion` in the root `gradle.properties`. Publishing includes the implementation JAR, sources, Javadoc, dependency metadata, and the plugin marker; test fixtures and the documentation subproject are not published. `publishPlugins` runs the plugin's unit tests, functional tests, and plugin validation before publishing.

Build and verify the release locally without credentials or uploading artifacts:

```sh
./gradlew build :plugin:publishToMavenLocal -Dmaven.repo.local="$PWD/build/publication-check"
./gradlew :plugin:integrationTest
```

The first command writes the implementation and marker publications to `build/publication-check/` without changing your usual Maven local repository. Portal validation with `--validate-only` also requires publishing credentials and contacts the Portal, but does not upload the release.

Create a [Plugin Portal account and API key](https://plugins.gradle.org/docs/publish-plugin), linked to the `gregwhitaker` GitHub account so the Portal can verify the plugin namespace. Add the key and secret to this repository's GitHub Actions secrets as `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`.

To release through GitHub Actions:

1. Set `pluginVersion` to the release version, update the changelog, and commit the release changes.
2. Push that commit and a matching version tag, for example `v0.2.0` for `pluginVersion=0.2.0`.
3. The [publish workflow](.github/workflows/publish.yml) checks the tag, builds the plugin and docs, runs unit/functional/Docker integration tests, validates the publication, then uploads it. It can also be dispatched manually against a matching version tag.

For example, from the intended release commit:

```sh
git tag v0.2.0
git push origin v0.2.0
```

To publish locally, provide `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` in your environment, or put `gradle.publish.key` and `gradle.publish.secret` in your user-level `~/.gradle/gradle.properties`. Keep credentials out of this repository. After running the validation commands above:

```sh
./gradlew :plugin:publishPlugins --validate-only
./gradlew :plugin:publishPlugins
```

The first publication requires [Portal approval](https://plugins.gradle.org/docs/publish-plugin#approval). Published versions are immutable; use a new version for subsequent releases. After approval, update the publication-status text in this README and the user guide. Applications can then apply the released plugin without a composite build:

```kotlin
plugins {
    id("io.github.gregwhitaker.aws-emulator") version "0.2.0"
}
```

## License

[Apache License 2.0](LICENSE).
