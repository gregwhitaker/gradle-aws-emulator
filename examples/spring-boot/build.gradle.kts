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
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.github.gregwhitaker.aws-emulator.spring-boot")
}

repositories { mavenCentral() }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation(platform("software.amazon.awssdk:bom:2.39.6"))
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:url-connection-client")
}

awsEmulator {
    port.set(0)
    springBoot {
        profiles.set(listOf("local"))
        mode.set(if (providers.gradleProperty("managed").isPresent) EmulatorMode.MANAGED else EmulatorMode.DEVELOPMENT)
    }
}
tasks.register<CreateSqsQueueTask>("createApplicationQueue") {
    queueName.set("spring-boot-example-events")
}
