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

awsEmulator {
    runtimes {
        register("integration")
        register("acceptance") { region.set("eu-west-1") }
        register("remote") {
            external {
                endpoint.set(providers.gradleProperty("remoteEndpoint"))
                credentialsFile.set(layout.projectDirectory.file(providers.gradleProperty("remoteCredentialsFile")))
                certificateFile.set(layout.projectDirectory.file(providers.gradleProperty("remoteCertificateFile")))
            }
        }
    }

    val application = resourceSets.register("application") {
        provision("queue", CreateSqsQueueTask::class.java) {
            queueName.set("named-runtime-events")
        }
    }

    for (name in listOf("integration", "acceptance", "remote")) {
        val suite = tasks.register<Test>(name + "Test") {
            description = "Tests against the " + name + " runtime."
            group = "verification"
            testClassesDirs = integration.output.classesDirs
            classpath = integration.runtimeClasspath
            useJUnitPlatform()
            systemProperty("suite", name)
            systemProperty("expected.region", if (name == "acceptance") "eu-west-1" else "us-east-1")
            systemProperty("connection.output", layout.buildDirectory.file("connections/" + name + ".txt").get().asFile.absolutePath)
            systemProperty("fail.suite", providers.gradleProperty("failSuite").getOrElse(""))
        }
        useWith(suite, name, application)
    }

    scenarios {
        register("integration") { runtimeName.set("integration"); resources(application) }
        register("acceptance") { runtimeName.set("acceptance"); resources(application) }
    }
}
