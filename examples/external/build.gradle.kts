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

import io.github.gregwhitaker.awsemulator.aws.kinesis.CreateKinesisStreamTask
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask

plugins {
    java
    id("io.github.gregwhitaker.aws-emulator")
}

repositories { mavenCentral() }

val integration = sourceSets.create("integration")
dependencies {
    "integrationImplementation"(platform("software.amazon.awssdk:bom:2.39.6"))
    "integrationImplementation"("software.amazon.awssdk:sqs")
    "integrationImplementation"("software.amazon.awssdk:url-connection-client")
    "integrationImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "integrationImplementation"("org.junit.jupiter:junit-jupiter")
    "integrationRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val queue = tasks.register<CreateSqsQueueTask>("createExampleQueue") {
    queueName.set("external-example-events")
}
val stream = tasks.register<CreateKinesisStreamTask>("createExampleStream") {
    streamName.set("external-example-records")
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Uses an existing emulator without starting or stopping it."
    group = "verification"
    testClassesDirs = integration.output.classesDirs
    classpath = integration.runtimeClasspath
    useJUnitPlatform()
    systemProperty("example.fail", providers.gradleProperty("failExample").getOrElse("false"))
}

awsEmulator {
    region.set(providers.gradleProperty("emulatorRegion").orElse("us-east-1"))
    external {
        endpoint.set(providers.gradleProperty("emulatorEndpoint"))
        credentialsFile.set(layout.projectDirectory.file(providers.gradleProperty("emulatorCredentialsFile")))
        certificateFile.set(layout.projectDirectory.file(providers.gradleProperty("emulatorCertificateFile")))
    }
    useWith(integrationTest, io.github.gregwhitaker.awsemulator.model.EmulatorMode.MANAGED, queue, stream)
    scenarios {
        register("default") { provision(queue, stream) }
    }
}
