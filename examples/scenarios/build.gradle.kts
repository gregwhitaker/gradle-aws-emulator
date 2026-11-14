/*
 * Copyright 2026 Greg Whitaker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

val infrastructure = awsEmulator.resourceSets.register("infrastructure") {
    provision("assets", CreateS3BucketTask::class.java) {
        bucketName.set("scenario-assets")
    }
    provision("products", CreateDynamoDbTableTask::class.java) {
        tableName.set("scenario-products")
        keySchema.add(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
        attributeDefinitions.add(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
    }
}
val assets = awsEmulator.resourceSets.register("assets") {
    include(infrastructure)
    seed("files", UploadS3DirectoryTask::class.java) {
        bucketName.set("scenario-assets")
        sourceDirectory.set(layout.projectDirectory.dir("fixtures/assets"))
        prefix.set("assets")
        metadata.put("fixture", "scenario-example")
    }
    seed("config", UploadS3ObjectTask::class.java) {
        bucketName.set("scenario-assets")
        objectKey.set("settings/app.json")
        sourceFile.set(layout.projectDirectory.file("fixtures/app.json"))
        contentType.set("application/json")
    }
}
val demo = awsEmulator.resourceSets.register("demo") {
    include(assets)
    seed("products", PutDynamoDbItemsTask::class.java) {
        tableName.set("scenario-products")
        itemsFile.set(layout.projectDirectory.file("fixtures/products.json"))
    }
}
val failedOrders = awsEmulator.resourceSets.register("failed-orders") {
    include(assets)
    seed("products", PutDynamoDbItemsTask::class.java) {
        tableName.set("scenario-products")
        itemsFile.set(layout.projectDirectory.file("fixtures/failed-orders.json"))
    }
}

awsEmulator {
    defaultMode.set(EmulatorMode.DEVELOPMENT)
    defaultScenario.set("demo")
    scenarios {
        register("demo") { resources(demo) }
        register("empty") { resources(infrastructure) }
        register("failed-orders") { resources(failedOrders) }
    }
}

tasks.register<DownloadS3ObjectTask>("downloadAsset") {
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
    useWith(verifyFixtures, EmulatorMode.MANAGED, demo)
}
