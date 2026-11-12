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

import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("build.java-conventions")
    id("build.jacoco")
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "io.github.gregwhitaker"
version = providers.gradleProperty("pluginVersion").get()
description = "Spring Boot development and debugging with AWS Emulator"
base { archivesName.set("gradle-aws-emulator-spring-boot") }
val projectWebsite = "https://github.com/gregwhitaker/gradle-aws-emulator"

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
    website.set(projectWebsite)
    vcsUrl.set("$projectWebsite.git")
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
        pom {
            name.set("AWS Emulator Spring Boot Gradle Plugin")
            description.set(project.description)
            url.set(projectWebsite)
            scm {
                url.set(projectWebsite)
                connection.set("scm:git:$projectWebsite.git")
                developerConnection.set("scm:git:git@github.com:gregwhitaker/gradle-aws-emulator.git")
            }
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
        }
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
    systemProperty("cognito.example", rootProject.layout.projectDirectory.dir("examples/cognito").asFile.absolutePath)
    inputs.files(rootProject.fileTree("examples/cognito") {
        include("build.gradle.kts", "src/**", "fixtures/**")
    }).withPropertyName("cognitoExample").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("springCloudAws.example", rootProject.layout.projectDirectory.dir("examples/spring-cloud-aws").asFile.absolutePath)
    inputs.files(rootProject.fileTree("examples/spring-cloud-aws") {
        include("build.gradle.kts", "src/**", "fixtures/**")
    }).withPropertyName("springCloudAwsExample").withPathSensitivity(PathSensitivity.RELATIVE)
    description = "Runs Spring Boot consumers against a real AWS Emulator."
}

tasks.named("publishPlugins") { dependsOn(tasks.named("check")) }
