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
    base
    id("build.jacoco")
    id("build.license")
}

description = "Build, verification, and documentation for the AWS Emulator Gradle plugin"

for (lifecycle in listOf("assemble", "check", "clean")) {
    tasks.named(lifecycle) {
        dependsOn(listOf("emulator-core", "testcontainers", "plugin", "spring-boot-plugin", "docs")
            .map { ":$it:$lifecycle" })
    }
}

// Preserve the 0.1 command while keeping real-emulator tests in their own source set.
tasks.register("dockerTest") {
    group = "verification"
    description = "Runs plugin and standalone Testcontainers integration suites against Docker."
    dependsOn("jacocoIntegrationTestReport")
}

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "8.14.3"
    distributionSha256Sum = "bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
}
