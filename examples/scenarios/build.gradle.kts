import io.github.gregwhitaker.awsemulator.aws.s3.*
import io.github.gregwhitaker.awsemulator.aws.dynamodb.*
import io.github.gregwhitaker.awsemulator.model.EmulatorMode
import software.amazon.awssdk.services.dynamodb.model.*

plugins {
    java
    id("io.github.gregwhitaker.aws-emulator")
}

repositories { mavenCentral() }
dependencies {
    testImplementation(platform("software.amazon.awssdk:bom:2.39.6"))
    testImplementation("software.amazon.awssdk:s3")
    testImplementation("software.amazon.awssdk:dynamodb")
    testImplementation("software.amazon.awssdk:url-connection-client")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val createAssets = tasks.register<CreateS3BucketTask>("createAssets") {
    bucketName.set("scenario-assets")
}
val createProducts = tasks.register<CreateDynamoDbTableTask>("createProducts") {
    tableName.set("scenario-products")
    keySchema.add(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
    attributeDefinitions.add(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
}
val uploadAssets = tasks.register<UploadS3DirectoryTask>("uploadAssets") {
    dependsOn(createAssets)
    bucketName.set(createAssets.flatMap { it.bucketName })
    sourceDirectory.set(layout.projectDirectory.dir("fixtures/assets"))
    prefix.set("assets")
    metadata.put("fixture", "scenario-example")
}
val uploadConfig = tasks.register<UploadS3ObjectTask>("uploadConfig") {
    dependsOn(createAssets)
    bucketName.set(createAssets.flatMap { it.bucketName })
    objectKey.set("settings/app.json")
    sourceFile.set(layout.projectDirectory.file("fixtures/app.json"))
    contentType.set("application/json")
}
val seedProducts = tasks.register<PutDynamoDbItemsTask>("seedProducts") {
    dependsOn(createProducts)
    tableName.set(createProducts.flatMap { it.tableName })
    itemsFile.set(layout.projectDirectory.file("fixtures/products.json"))
}
val seedFailedOrders = tasks.register<PutDynamoDbItemsTask>("seedFailedOrders") {
    dependsOn(createProducts)
    tableName.set(createProducts.flatMap { it.tableName })
    itemsFile.set(layout.projectDirectory.file("fixtures/failed-orders.json"))
}

awsEmulator {
    port.set(0)
    defaultScenario.set("demo")
    scenarios {
        register("demo") {
            provision(createAssets, createProducts)
            seed(uploadAssets, uploadConfig, seedProducts)
        }
        register("empty") { provision(createAssets, createProducts) }
        register("failed-orders") {
            provision(createAssets, createProducts)
            seed(uploadAssets, uploadConfig, seedFailedOrders)
        }
    }
}

tasks.register<DownloadS3ObjectTask>("downloadAsset") {
    mode.set(EmulatorMode.DEVELOPMENT)
    bucketName.set("scenario-assets")
    objectKey.set("assets/nested/message.txt")
    outputFile.set(layout.buildDirectory.file("downloads/message.txt"))
}

val verifyScenario = tasks.register<Test>("verifyScenario") {
    description = "Seeds and checks the selected persistent development scenario."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    systemProperty("scenario", providers.gradleProperty("awsEmulator.scenario").getOrElse("demo"))
}
val verifyFixtures = tasks.register<Test>("verifyFixtures") {
    description = "Checks the same fixtures in a fresh managed emulator and cleans up afterwards."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    systemProperty("scenario", "demo")
}
awsEmulator {
    useWith(verifyScenario, EmulatorMode.DEVELOPMENT, "seedAwsEmulator")
    useWith(verifyFixtures, EmulatorMode.MANAGED, uploadAssets, uploadConfig, seedProducts)
}
