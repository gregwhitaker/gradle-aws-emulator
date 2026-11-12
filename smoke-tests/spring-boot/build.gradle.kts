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
import io.github.gregwhitaker.awsemulator.emulator.StartAwsEmulatorTask
import io.github.gregwhitaker.awsemulator.model.EmulatorMode
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    // The companion must obtain the core implementation through its published dependency.
    id("io.github.gregwhitaker.aws-emulator.spring-boot")
}

repositories { mavenCentral() }

// Avoid Boot's main-class discovery probing not-yet-created class directories
// during configuration, which invalidates the first build's configuration cache.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    mainClass.set("example.Application")
}

awsEmulator {
    dockerExecutable.set("no-docker-in-publication-smoke-tests")
    springBoot { profiles.set(listOf("publication-smoke")) }
}

check(plugins.hasPlugin("io.github.gregwhitaker.aws-emulator"))
tasks.named<StartAwsEmulatorTask>("startAwsEmulator").get()
val queue = tasks.register<CreateSqsQueueTask>("createSmokeQueue") {
    queueName.set("publication-smoke")
}.get()
check(queue.mode.get() == EmulatorMode.MANAGED)
check(awsEmulator.springBoot.mode.get() == EmulatorMode.DEVELOPMENT)

// Realize the companion's BootRun tasks without launching an application or emulator.
val local = tasks.named<BootRun>("bootRunLocal").get()
val debug = tasks.named<BootRun>("bootRunLocalDebug").get()
check(!local.debugOptions.enabled.get())
check(debug.debugOptions.enabled.get())
check(local.jvmArgumentProviders.last().asArguments().contains("-Dspring.profiles.active=publication-smoke"))
