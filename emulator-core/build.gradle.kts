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

description = "Shared image, credential, and TLS support for AWS Emulator"
base { archivesName.set("aws-emulator-core") }

publishing.publications.named<MavenPublication>("mavenJava") {
    artifactId = "aws-emulator-core"
    pom.name.set("AWS Emulator Core")
}

dependencies {
    api(platform("software.amazon.awssdk:bom:2.39.6"))
    api("software.amazon.awssdk:auth")
    implementation("software.amazon.awssdk:profiles")
}
