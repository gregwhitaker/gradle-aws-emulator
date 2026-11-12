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
