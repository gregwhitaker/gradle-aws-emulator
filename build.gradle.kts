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
    jacoco
    id("com.adarshr.test-logger") version "4.0.0" apply false
    id("io.github.gmazzo.gradle.testkit.jacoco") version "1.1.2" apply false
    id("build.license")
}

description = "Build, verification, and documentation for the AWS Emulator Gradle plugin"

repositories { mavenCentral() }
jacoco { toolVersion = providers.gradleProperty("jacocoVersion").get() }

val coverageModules = listOf(project(":plugin"), project(":spring-boot-plugin"))
for ((reportName, suites) in listOf(
    "jacocoTestReport" to listOf("test", "functionalTest"),
    "jacocoIntegrationTestReport" to listOf("integrationTest")
)) {
    tasks.register<JacocoReport>(reportName) {
        group = "verification"
        description = "Generates combined coverage for ${suites.joinToString()} in both plugins."
        dependsOn(coverageModules.map { "${it.path}:$reportName" })
        classDirectories.from(coverageModules.map { it.layout.buildDirectory.dir("classes/java/main") })
        sourceDirectories.from(coverageModules.map { it.layout.projectDirectory.dir("src/main/java") })
        executionData.from(coverageModules.flatMap { module ->
            suites.map { module.layout.buildDirectory.file("jacoco/$it.exec") }
        })
        reports {
            html.required.set(true)
            html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/${suites.first()}/html"))
            xml.required.set(true)
            xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/${suites.first()}/$reportName.xml"))
            csv.required.set(false)
        }
    }
}

tasks.named("assemble") { dependsOn(":plugin:assemble", ":spring-boot-plugin:assemble", ":docs:assemble") }
tasks.named("check") { dependsOn(":plugin:check", ":spring-boot-plugin:check", ":docs:check", "jacocoTestReport") }
tasks.named("clean") { dependsOn(":plugin:clean", ":spring-boot-plugin:clean", ":docs:clean") }

// Preserve the 0.1 command while keeping real-emulator tests in their own source set.
tasks.register("dockerTest") {
    group = "verification"
    description = "Runs both integration suites against Docker and generates coverage reports."
    dependsOn("jacocoIntegrationTestReport")
}

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "8.14.3"
    distributionSha256Sum = "bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
}
