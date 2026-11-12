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

import buildlogic.testing.useExample
import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    id("build.java-conventions")
    id("build.jacoco")
    id("build.publishing")
}

description = "Spring Boot development and debugging with AWS Emulator"
base { archivesName.set("gradle-aws-emulator-spring-boot") }

val functionalSourceSet = sourceSets["functional"]
val integrationSourceSet = sourceSets["integration"]

dependencies {
    implementation(project(":plugin"))
    // The consuming application supplies Spring Boot. Its classes are resolved from that plugin's loader.
    testImplementation(project(":plugin"))
    for (testSourceSet in listOf(functionalSourceSet, integrationSourceSet)) {
        add(testSourceSet.implementationConfigurationName, project(":spring-boot-plugin"))
        add(testSourceSet.implementationConfigurationName, testFixtures(project(":plugin")))
        add(testSourceSet.implementationConfigurationName, gradleTestKit())
    }
}

gradlePlugin {
    testSourceSets(functionalSourceSet, integrationSourceSet)
    plugins {
        create("awsEmulatorSpringBoot") {
            id = "io.github.gregwhitaker.aws-emulator.spring-boot"
            implementationClass = "io.github.gregwhitaker.awsemulator.boot.AwsEmulatorSpringBootPlugin"
            displayName = "AWS Emulator Spring Boot Gradle Plugin"
            description = project.description
            tags.set(listOf("aws", "aws-emulator", "spring-boot", "docker", "development"))
            compatibility { features { configurationCache = true } }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") artifactId = "gradle-aws-emulator-spring-boot"
        pom.name.set("AWS Emulator Spring Boot Gradle Plugin")
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("test.gradle.version", providers.gradleProperty("testGradleVersion").getOrElse(""))
    systemProperty("test.gradle.installation", providers.gradleProperty("testGradleInstallation").getOrElse(""))
}
tasks.named<Test>("functionalTest") {
    description = "Verifies Spring Boot compatibility without Docker."
}
tasks.named<Test>("integrationTest") {
    useExample("cognito")
    useExample("spring-cloud-aws")
    description = "Runs Spring Boot consumers against a real AWS Emulator."
}
