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
import io.github.gregwhitaker.awsemulator.aws.sqs.PublishSqsTask
import io.github.gregwhitaker.awsemulator.aws.ssm.PutSsmParameterTask
import io.github.gregwhitaker.awsemulator.aws.secretsmanager.PutSecretTask
import io.github.gregwhitaker.awsemulator.model.EmulatorMode

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.github.gregwhitaker.aws-emulator.spring-boot")
}

repositories { mavenCentral() }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation(platform("io.awspring.cloud:spring-cloud-aws-dependencies:3.4.0"))
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sqs")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-parameter-store")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-secrets-manager")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val applicationResources = awsEmulator.resourceSets.register("application") {
    provision("queue", CreateSqsQueueTask::class.java) {
        queueName.set("spring-cloud-events")
    }
    provision("configuration", PutSsmParameterTask::class.java) {
        parameterName.set("/spring-cloud-example/example.greeting")
        value.set("hello-from-parameter-store")
    }
    provision("secret", PutSecretTask::class.java) {
        secretName.set("/spring-cloud-example/credentials")
        value.set("""{"example.token":"local-example-token"}""")
    }
    seed("messages", PublishSqsTask::class.java) {
        queueNames.add("spring-cloud-events")
        messages.from(layout.projectDirectory.file("fixtures/messages.txt"))
    }
}

awsEmulator {
    port.set(0)
    useWith(tasks.named<Test>("test"), EmulatorMode.MANAGED, applicationResources)
    scenarios { register("default") { resources(applicationResources) } }
    springBoot {
        resourceSets.add(applicationResources)
        mode.set(if (providers.gradleProperty("managed").isPresent) EmulatorMode.MANAGED else EmulatorMode.DEVELOPMENT)
    }
}

springBoot { mainClass.set("example.Application") }
tasks.test {
    useJUnitPlatform()
    systemProperty("spring.profiles.active", "local")
    systemProperty("example.connectionFile", "build/test-connection.txt")
    systemProperty("example.fail", providers.gradleProperty("failExample").getOrElse("false"))
}
