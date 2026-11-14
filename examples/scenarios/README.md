# Development scenarios and data fixtures

Run these commands from the repository root with Docker running:

```shell
./gradlew -p examples/scenarios seedAwsEmulator --configuration-cache
./gradlew -p examples/scenarios verifyScenario --configuration-cache
./gradlew -p examples/scenarios resetAwsEmulator -PawsEmulator.scenario=empty --configuration-cache
./gradlew -p examples/scenarios resetAwsEmulatorFailedOrders --configuration-cache
./gradlew -p examples/scenarios verifyScenario -PawsEmulator.scenario=failed-orders --configuration-cache
./gradlew -p examples/scenarios listS3Objects --bucketName=scenario-assets --prefix=assets/
./gradlew -p examples/scenarios downloadAsset
./gradlew -p examples/scenarios purgeDynamoDbTable --tableName=scenario-products
./gradlew -p examples/scenarios stopAwsEmulator
```

`demo` loads S3 files and two DynamoDB products. `empty` creates the bucket and table without fixtures. `failed-orders` loads a failed payment record. `replay` loads previously exported objects and items. Use **reset** when switching datasets: seed replaces matching object/item keys but preserves unrelated data. The default `RUNTIME` reset scope removes **all** resources in this project's development emulator, then provisions and seeds the selected scenario. Other AWS services in that emulator are reset too.

## Selective reset

The `infrastructure` resource set declares cleanup for only `scenario-assets` and `scenario-products`. Use `RESOURCES` scope to remove and recreate these resources while preserving the running emulator and unrelated buckets, tables, queues, and other resources:

```shell
./gradlew -p examples/scenarios resetAwsEmulator -PawsEmulator.resetScope=RESOURCES --configuration-cache
./gradlew -p examples/scenarios resetAwsEmulator -PawsEmulator.resetScope=RESOURCES -PawsEmulator.scenario=empty
./gradlew -p examples/scenarios cleanAwsEmulator
```

`cleanAwsEmulator` runs only explicit cleanup, without provisioning or seeding. Cleanup tolerates missing resources, so it can run repeatedly or before the first seed. A cleanup failure blocks subsequent cleanup and all reseeding. The scenario's `resetScope` can also be set to `ScenarioResetScope.RESOURCES` in the build; the project property overrides it. Ordinary seed commands and managed resource-set attachments never run cleanup.

## Export and replay

Capture a live dataset, including application changes since seeding, and replay it later:

```shell
./gradlew -p examples/scenarios seedAwsEmulator
./gradlew -p examples/scenarios exportScenario --configuration-cache
./gradlew -p examples/scenarios resetAwsEmulator -PawsEmulator.scenario=empty -PawsEmulator.resetScope=RESOURCES
./gradlew -p examples/scenarios resetAwsEmulatorReplay -PawsEmulator.resetScope=RESOURCES --configuration-cache
./gradlew -p examples/scenarios stopAwsEmulator
```

`exportScenarioObjects` writes `build/fixtures/scenario/objects.json` with current S3 object bytes, keys, content types, and user metadata. `exportScenarioItems` writes `build/fixtures/scenario/items.json` with DynamoDB AttributeValue JSON, preserving binary values, number precision, sets, maps, and lists. `exportScenario` runs both. These tasks require an existing runtime and resources; they do not start Docker or seed anything.

`replay` provisions the same bucket and table and loads those files. Its inputs are plain file paths, so replay never reruns export. Resource-scoped reset removes extra data before replay, keeping unrelated resources intact. `seedAwsEmulatorReplay` can merge the captured dataset instead, replacing only matching object/item keys.

Stop writers while capturing or replaying. Exports contain actual data and are limited to 16 MiB per file, including base64 overhead. Successful exports replace files; failures retain the previous file. Empty datasets are supported. Files do not include resource configuration, S3 version history, other object headers, or data from other services. Move files outside `build/` if they should survive Gradle `clean`, and review their contents before sharing. See the [export/replay guide](../../docs/src/docs/asciidoc/scenarios.adoc#export-and-replay) for format and limits.

## Runtime selection

The example uses an automatically allocated port. Connection files are under `.aws-emulator/development/.aws/emulator/<port>/`.

The build sets `awsEmulator.defaultMode` to `EmulatorMode.DEVELOPMENT`, so custom resource tasks such as `downloadAsset` use the persistent instance without setting their own `mode`. Individual tasks can override this default. Resource sets use the mode selected by their consumer or scenario, so `verifyFixtures` still uses managed resources.

To check the data tasks in an isolated managed runtime that is cleaned up automatically:

```shell
./gradlew -p examples/scenarios verifyFixtures --configuration-cache
```

The `infrastructure`, `assets`, `demo`, and `failed-orders` resource sets declare each resource and fixture once. Sets include shared prerequisites; scenarios attach their development instances, while `verifyFixtures` attaches the managed `demo` instance. Both modes can run in the same invocation:

```shell
./gradlew -p examples/scenarios resetAwsEmulator verifyFixtures --configuration-cache
./gradlew -p examples/scenarios stopAwsEmulator
```

The managed instance is removed after verification. The development instance remains available with the selected scenario. Repeat the combined command to verify configuration-cache reuse.
