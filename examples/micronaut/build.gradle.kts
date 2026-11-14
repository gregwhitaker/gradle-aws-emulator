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

import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
import io.github.gregwhitaker.awsemulator.model.EmulatorMode

plugins {
    id("io.micronaut.application")
    id("io.github.gregwhitaker.aws-emulator")
}

repositories { mavenCentral() }

micronaut {
    version.set(providers.gradleProperty("micronautVersion").getOrElse("4.10.24"))
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("example.*")
    }
}

dependencies {
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut.aws:micronaut-aws-sdk-v2")
    implementation(platform("software.amazon.awssdk:bom:2.39.6"))
    implementation("software.amazon.awssdk:sqs") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
    runtimeOnly("ch.qos.logback:logback-classic")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
application { mainClass.set("example.Application") }

val applicationMode = providers.gradleProperty("development").map {
    if (it.toBoolean()) EmulatorMode.DEVELOPMENT else EmulatorMode.MANAGED
}.orElse(EmulatorMode.MANAGED)
val createApplicationQueue = tasks.register<CreateSqsQueueTask>("createApplicationQueue") {
    queueName.set("micronaut-example-events")
    mode.set(applicationMode)
}
val createTestQueue = tasks.register<CreateSqsQueueTask>("createTestQueue") {
    queueName.set("micronaut-example-events")
}
val run = tasks.named<JavaExec>("run") {
    systemProperty("example.fail", providers.gradleProperty("failExample").getOrElse("false"))
}
val tests = tasks.named<Test>("test") {
    useJUnitPlatform()
    systemProperty("example.fail", providers.gradleProperty("failExample").getOrElse("false"))
}

awsEmulator {
    useWith(run, applicationMode, createApplicationQueue)
    // Tests always get a fresh managed instance, even when run uses development mode.
    useWith(tests, EmulatorMode.MANAGED, createTestQueue)
}
