# DynamoDB example

Demonstrates creating and initializing a DynamoDB table before JUnit 5 integration tests, then reading items and querying a global secondary index with AWS SDK for Java v2.

Requires Java 17 or later, a running local Docker engine configured for Linux containers, and the repository's Gradle wrapper. No AWS account or AWS CLI is required. See the [examples index](../README.md) for shared setup.

## Run

From the **repository root**:

```sh
./gradlew -p examples/dynamodb integrationTest --configuration-cache
```

Run the same command again to exercise configuration-cache reuse. Each run provisions a fresh table, initializes it, and executes the tests. Open `examples/dynamodb/build/reports/tests/integrationTest/index.html` for the test report.

The plugin starts a managed emulator on an automatically selected port and supplies the test JVM with the endpoint, region, credentials, and TLS trust. It removes the managed container and connection files after its consumers finish, including after an ordinary test failure.

## Table and initializer

The [build script](build.gradle.kts) registers `createProducts` and makes `integrationTest` depend on it. The task creates:

| Setting | Value |
| --- | --- |
| Table name | `example-products` |
| Partition key | `id`, a string |
| Global secondary index | `by-category`, keyed by the string attribute `category`, projecting all attributes |
| Stream view | `NEW_IMAGE` |
| Initializer class | `example.buildsrc.dynamodb.ProductTableInitializer` |

[ProductTableInitializer](buildSrc/src/main/java/example/buildsrc/dynamodb/ProductTableInitializer.java) receives the plugin's authenticated `DynamoDbClient` and inserts `product-1`, an example widget in the `widgets` category. The [integration tests](src/integration/java/example/DynamoDbIntegrationTest.java) read this item by its primary key and query it through `by-category`.

## Build classpath

The initializer lives in `buildSrc` so it is available to the provisioning task. [buildSrc/settings.gradle.kts](buildSrc/settings.gradle.kts) includes this repository and substitutes the plugin dependency with the local `:plugin` project. [buildSrc/build.gradle.kts](buildSrc/build.gradle.kts) declares the plugin and DynamoDB SDK together, keeping the initializer and plugin on the same SDK classpath.

The main build therefore applies the plugin without a version or a separate `pluginManagement` include. Its integration tests declare their own SDK dependencies.

## Persistent development instance

To create and inspect the same table in a development instance:

```sh
./gradlew -p examples/dynamodb createProducts --mode=DEVELOPMENT
./gradlew -p examples/dynamodb listDynamoDbTables
./gradlew -p examples/dynamodb stopAwsEmulator
```

The development instance remains running between Gradle invocations and is separate from the managed test instance. The initializer runs when the table is newly created. Stopping or restarting the instance discards the table and its items; run `stopAwsEmulator` when finished.

See the [DynamoDB guide](../../docs/src/docs/asciidoc/dynamodb.adoc) for all table properties, initializer requirements, and table deletion commands, or the [main README](../../README.md) for general plugin configuration.
