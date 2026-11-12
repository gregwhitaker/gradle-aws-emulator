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

`demo` loads S3 files and two DynamoDB products. `empty` creates the bucket and table without fixtures. `failed-orders` loads a failed payment record. Use **reset** when switching datasets: seed replaces matching object/item keys but preserves unrelated data. Reset removes **all** resources in this project's development emulator, then provisions and seeds the selected scenario. Other AWS services in that emulator are reset too.

The example uses an automatically allocated port. Connection files are under `.aws-emulator/development/.aws/emulator/<port>/`.

To check the data tasks in an isolated managed runtime that is cleaned up automatically:

```shell
./gradlew -p examples/scenarios verifyFixtures --configuration-cache
```

Run managed verification separately from development scenario commands. A selected scenario binds its resource tasks and their resource prerequisites to DEVELOPMENT mode for that invocation.
