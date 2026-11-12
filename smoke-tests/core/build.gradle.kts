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

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.gregwhitaker.awsemulator.aws.dynamodb.CreateDynamoDbTableTask
import io.github.gregwhitaker.awsemulator.emulator.StartAwsEmulatorTask
import io.github.gregwhitaker.awsemulator.model.EmulatorMode
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.io.ByteArrayOutputStream
import java.net.URI

plugins {
    java
    id("io.github.gregwhitaker.aws-emulator")
}

awsEmulator {
    dockerExecutable.set("no-docker-in-publication-smoke-tests")
    // The NO-SOURCE test exercises consumer wiring without starting an emulator.
    useWith(tasks.named<Test>("test"))
}

// Realize public task types, including one whose API uses transitive AWS SDK types.
tasks.named<StartAwsEmulatorTask>("startAwsEmulator").get()
val table = tasks.register<CreateDynamoDbTableTask>("createSmokeTable") {
    tableName.set("publication-smoke")
}.get()
check(table.mode.get() == EmulatorMode.MANAGED)
check(table.provisionedThroughput.get().readCapacityUnits() == 100L)

abstract class VerifyPublishedDependencies : DefaultTask() {
    @TaskAction
    fun verify() {
        // Construct a client without making requests or using the host's AWS credentials.
        S3Client.builder()
            .endpointOverride(URI.create("https://127.0.0.1:1"))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("smoke", "smoke")))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build().use { check(it.serviceName() == "s3") }
        check(ObjectMapper().readTree("""{"published":true}""").path("published").asBoolean())
        ZipArchiveOutputStream(ByteArrayOutputStream()).use { it.finish() }
    }
}

val verifyPublishedDependencies = tasks.register<VerifyPublishedDependencies>("verifyPublishedDependencies")
tasks.named("check") { dependsOn(verifyPublishedDependencies) }
