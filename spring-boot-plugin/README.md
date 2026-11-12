# AWS Emulator Spring Boot Gradle Plugin

Run and debug Spring Boot applications with a local AWS Emulator. The companion plugin adds `bootRunLocal` and `bootRunLocalDebug`, provisions registered AWS resources before application startup, and supplies the application's JVM with emulator connection settings.

Plugin ID: **`io.github.gregwhitaker.aws-emulator.spring-boot`**. Version **0.2.0** is implemented in this repository and is available from a checkout until publication to the Gradle Plugin Portal.

The companion automatically applies the [base AWS Emulator plugin](../README.md). Your application selects and applies its own Spring Boot version. The companion is a separate artifact, `io.github.gregwhitaker:gradle-aws-emulator-spring-boot`, and does not bring Spring Boot into your build transitively.

## Requirements

- Java 17+ and Gradle 8.14+.
- A local Docker engine running Linux containers; AMD64 and ARM64 are supported.
- A Spring Boot application with `org.springframework.boot` and the Java or Kotlin JVM plugin applied.

Compatibility coverage in this repository includes Spring Boot **3.5.16** and **4.1.2**. The emulator image is built on first use and reused on subsequent runs. No AWS account or AWS CLI is needed.

## Apply from a checkout

Include the repository root as a composite build in your existing Spring Boot application's settings. Adjust the path to your checkout.

### Kotlin DSL

`settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("../gradle-aws-emulator")
    repositories { gradlePluginPortal() }
}
```

`build.gradle.kts`:

```kotlin
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.github.gregwhitaker.aws-emulator.spring-boot")
}

awsEmulator {
    port.set(0)
    springBoot {
        profiles.set(listOf("local"))
    }
}

tasks.register<CreateSqsQueueTask>("createApplicationQueue") {
    queueName.set("application-events")
}
```

### Groovy DSL

`settings.gradle`:

```groovy
pluginManagement {
    includeBuild('../gradle-aws-emulator')
    repositories { gradlePluginPortal() }
}
```

`build.gradle`:

```groovy
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask

plugins {
    id 'java'
    id 'org.springframework.boot' version '4.1.2'
    id 'io.github.gregwhitaker.aws-emulator.spring-boot'
}

awsEmulator {
    port.set(0)
    springBoot {
        profiles.set(['local'])
    }
}

tasks.register('createApplicationQueue', CreateSqsQueueTask) {
    queueName.set('application-events')
}
```

Keep your application's existing dependencies and repositories. The application declares its own AWS SDK dependencies; see the [runnable example build](../examples/spring-boot/build.gradle.kts) for a complete configuration.

Plugin application order does not matter. The local run tasks are registered once Spring Boot and Java are present. Applying the companion alone does not start Docker. Setting `awsEmulator.port` to `0` selects an available host port.

## Run and debug

Run these commands from your application's project:

```sh
./gradlew bootRunLocal --configuration-cache
./gradlew bootRunLocal --args='--server.port=8081'
./gradlew bootRunLocalDebug
```

Both tasks start or reuse the emulator, run resource setup, activate the `local` Spring profile by default, and launch the application. `bootRunLocalDebug` listens on `127.0.0.1:5005` and waits for a debugger to attach. Configure `debugPort` and `debugSuspend` to change those defaults.

The tasks use Spring Boot's `BootRun` type and inherit the existing `bootRun` main class, classpath, Java launcher, working directory, environment, JVM and application arguments, system properties, argument providers, and optimized-launch setting. Configure custom task actions directly on the local tasks. Command-line `--args` overrides inherited application arguments. Ordinary `bootRun` remains independently configured.

Configuration-cache reuse is supported. When using automatic main-class discovery, a clean compilation can invalidate Spring Boot's first cache entry; subsequent unchanged runs reuse it.

### Emulator lifecycle

The default `EmulatorMode.DEVELOPMENT` keeps the emulator running after the application exits, so resources remain available across application restarts. Use the base plugin's tasks to inspect or stop it:

```sh
./gradlew statusAwsEmulator
./gradlew logsAwsEmulator
./gradlew stopAwsEmulator
```

Stopping or restarting the emulator discards its in-memory resources. `stopAwsEmulator` also removes the development container and generated connection files.

For a fresh build-owned instance with automatic cleanup after the application finishes, including ordinary application failures, select managed mode:

```kotlin
import io.github.gregwhitaker.awsemulator.model.EmulatorMode

awsEmulator {
    springBoot {
        mode.set(EmulatorMode.MANAGED)
    }
}
```

Managed and development instances are separate. See the [base plugin lifecycle documentation](../README.md#managed-tests-and-java-execution) for sharing and cleanup behavior.

## Automatic resource creation

Both local tasks discover registered resource-creation tasks in the application's project and execute them before startup. The queue in the quick start needs only its name; the companion supplies setup dependencies and selects the application's runtime as the resource task's default mode.

Discovery covers S3 buckets, SQS queues and DLQs, SNS topics and subscriptions, DynamoDB tables and initializers, SSM parameters, Secrets Manager secrets, EventBridge buses/rules/targets, Kinesis streams, KMS keys and aliases, and CloudWatch log groups and streams. It uses the core `@AwsEmulatorSetupTask` annotation, which is inherited by subclasses. Custom `AbstractAwsResourceTask` implementations can opt in with the same annotation.

Declare ordering between resources with `dependsOn`, such as a log stream depending on its log group. Add message publishing, data fixtures, and custom preparation through `setupTasks` or explicit dependencies. Explicit resource modes must match the application's mode; a conflict fails before startup.

Exclude test-only resources from automatic discovery by task name or path:

```kotlin
awsEmulator {
    springBoot {
        excludedSetupTasks.add("createTestQueue")
    }
}
```

Exclusions affect discovery; explicit dependencies still execute. To select setup tasks manually, disable discovery and configure the resource modes yourself. For the queue registered above:

```kotlin
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask

awsEmulator {
    springBoot {
        autoCreateResources.set(false)
        setupTasks.add("createApplicationQueue")
    }
}
tasks.named<CreateSqsQueueTask>("createApplicationQueue") {
    mode.set(awsEmulator.springBoot.mode)
}
```

Use reusable resource sets for consumers that need different modes in the same build, or register separate ordinary setup tasks. See the [Spring Boot guide](../docs/src/docs/asciidoc/spring-boot.adoc) for more setup details.

## Configuration

Use `awsEmulator.springBoot.resourceSets.add(applicationResources)` to attach a reusable set declared in `awsEmulator.resourceSets`. The local tasks provision and seed it in their selected mode. Attach the same definition to managed tests with `awsEmulator.useWith(test, EmulatorMode.MANAGED, applicationResources)` or to a scenario with `resources(applicationResources)`. Each mode gets separate tasks; generated resource-set tasks are excluded from automatic discovery, and explicit sets still run when `autoCreateResources` is false. See the [resource-set guide](../docs/src/docs/asciidoc/scenarios.adoc) for composition and ordering.

Configure these properties in `awsEmulator { springBoot { ... } }`:

| Property | Default | Behavior |
| --- | --- | --- |
| `mode` | `EmulatorMode.DEVELOPMENT` | Use the persistent development instance or a build-owned `MANAGED` instance. |
| `profiles` | `["local"]` | Set `spring.profiles.active`; an empty list adds no profile argument. |
| `jvmArgs` | `[]` | Additional JVM arguments for both local tasks. |
| `autoCreateResources` | `true` | Discover resource-provisioning tasks and run them before the application. |
| `excludedSetupTasks` | `[]` | Task names or paths to omit from automatic discovery. |
| `setupTasks` | `[]` | Additional task names or paths that both local tasks depend on. |
| `resourceSets` | `[]` | Reusable definitions to provision and seed in the application's mode. |
| `debugPort` | `5005` | Loopback debugger port, from 1 to 65535. |
| `debugSuspend` | `true` | Wait for a debugger before starting the application in `bootRunLocalDebug`. |

Configure emulator settings such as `port`, `region`, and image selection in the base [`awsEmulator` extension](../docs/src/docs/asciidoc/configuration.adoc).

The previous top-level `springBootEmulator` block remains an alias for the same settings for compatibility with existing builds.

The plugin supplies the endpoint, region, generated credentials, and TLS trust just before the JVM starts. Use SDK clients that honor external connection configuration. See [Credentials and TLS](../docs/src/docs/asciidoc/connections.adoc) for SDK setup and trust-store requirements.

## Try the included example

From the repository root, run the Spring Boot console application that sends and receives an SQS message and exits:

```sh
./gradlew -p examples/spring-boot bootRunLocal --configuration-cache
./gradlew -p examples/spring-boot bootRunLocal --configuration-cache
./gradlew -p examples/spring-boot stopAwsEmulator
```

The second invocation reuses the development emulator. Add `-Pmanaged=true` to select automatic cleanup in this example, or run `bootRunLocalDebug` to attach a debugger. See the [example README](../examples/spring-boot/README.md) for failure-cleanup commands and application source.

For Spring framework integration, run the [Spring Cloud AWS example](../examples/spring-cloud-aws/README.md). Its pinned Boot 3.5.16 / Spring Cloud AWS 3.4.0 pairing covers `@SqsListener`, async clients, `SqsTemplate`, and required SSM/Secrets Manager imports, including managed cleanup and configuration-cache reuse. It uses the same resource definitions for tests, local runs, and scenarios.

## License

[Apache License 2.0](../LICENSE).
