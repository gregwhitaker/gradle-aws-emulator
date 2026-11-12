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
