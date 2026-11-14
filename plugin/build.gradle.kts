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
import buildlogic.testing.useFrameworkExamples
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    `java-test-fixtures`
    id("build.java-conventions")
    id("build.jacoco")
    id("build.publishing")
}

description = "Gradle lifecycle, resource provisioning, and test integration for AWS Emulator"
base { archivesName.set("gradle-aws-emulator") }

val functionalSourceSet = sourceSets["functional"]
val integrationSourceSet = sourceSets["integration"]

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") artifactId = "gradle-aws-emulator"
        pom.name.set("AWS Emulator Gradle Plugin")
    }
}

dependencies {
    implementation(project(":emulator-core"))
    implementation(platform("software.amazon.awssdk:bom:2.39.6"))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:sqs") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:sns") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:dynamodb") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
    for (service in listOf("ssm", "secretsmanager", "eventbridge", "kinesis", "kms", "cloudwatchlogs", "cognitoidentityprovider", "sesv2")) {
        implementation("software.amazon.awssdk:$service") {
            exclude(group = "software.amazon.awssdk", module = "apache-client")
            exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        }
    }
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.6")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testFixturesImplementation(gradleTestKit())
    for (testSourceSet in listOf(functionalSourceSet, integrationSourceSet)) {
        add(testSourceSet.implementationConfigurationName, project(":plugin"))
        add(testSourceSet.implementationConfigurationName, testFixtures(project(":plugin")))
        add(testSourceSet.implementationConfigurationName, gradleTestKit())
    }
}

gradlePlugin {
    testSourceSets(functionalSourceSet, integrationSourceSet)
    plugins {
        create("awsEmulator") {
            id = "io.github.gregwhitaker.aws-emulator"
            implementationClass = "io.github.gregwhitaker.awsemulator.AwsEmulatorPlugin"
            displayName = "AWS Emulator Gradle Plugin"
            description = project.description
            tags.set(listOf("aws", "aws-emulator", "docker", "testing", "integration-testing"))
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("test.gradle.version", providers.gradleProperty("testGradleVersion").getOrElse(""))
    systemProperty("test.gradle.installation", providers.gradleProperty("testGradleInstallation").getOrElse(""))
}

val functionalTest = tasks.named<Test>("functionalTest") {
    description = "Runs Gradle TestKit tests without Docker."
}

tasks.named<Test>("integrationTest") {
    useExample("external")
    useExample("named-runtimes")
    useExample("ses")
    useExample("multi-project", multiProject = true)
    useExample("scenarios")
    useExample("services")
    description = "Runs integration tests against a real Docker daemon and AWS Emulator."
}

for (testTask in listOf(functionalTest, tasks.named<Test>("integrationTest"))) {
    testTask.configure { useFrameworkExamples("micronaut", "ktor") }
}

val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
