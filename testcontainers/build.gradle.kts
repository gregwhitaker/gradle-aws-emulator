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

plugins {
    `java-library`
    id("build.java-conventions")
    id("build.jacoco")
    id("build.library-publishing")
}

description = "Standalone Testcontainers support for AWS Emulator"
base { archivesName.set("testcontainers-aws-emulator") }

publishing.publications.named<MavenPublication>("mavenJava") {
    artifactId = "testcontainers-aws-emulator"
    pom.name.set("AWS Emulator Testcontainers")
}

dependencies {
    api(project(":emulator-core"))
    // Publish the BOM directly so Maven consumers can resolve the HTTP transport's version too.
    api(platform("software.amazon.awssdk:bom:2.39.6"))
    api("org.testcontainers:testcontainers:2.0.5")
    api("software.amazon.awssdk:url-connection-client")
    "integrationImplementation"(project(":testcontainers"))
    "integrationImplementation"("software.amazon.awssdk:s3")
    "integrationImplementation"("software.amazon.awssdk:sqs")
    "integrationImplementation"("software.amazon.awssdk:sts")
    "integrationImplementation"("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    "integrationRuntimeOnly"("org.slf4j:slf4j-simple:2.0.17")
}

tasks.named<Test>("integrationTest") {
    description = "Verifies standalone Testcontainers lifecycle, signed AWS calls, and TLS against Docker."
}
