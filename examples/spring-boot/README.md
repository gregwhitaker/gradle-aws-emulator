# Spring Boot example

This console application starts Spring Boot, sends and receives an SQS message over signed HTTPS, and exits. The companion plugin applies the base emulator plugin, provisions the queue before the application, activates the `local` profile, and configures credentials and TLS.

From the repository root, with Java 17+ and Docker available:

```sh
./gradlew -p examples/spring-boot bootRunLocal --configuration-cache
./gradlew -p examples/spring-boot bootRunLocal --configuration-cache
./gradlew -p examples/spring-boot listSqsQueues
./gradlew -p examples/spring-boot stopAwsEmulator
```

The default development instance survives the application exit and is reused on the second invocation. Stop removes the container and its resources.

For a fresh managed instance with automatic cleanup, including after an application failure:

```sh
./gradlew -p examples/spring-boot bootRunLocal -Pmanaged=true --configuration-cache
./gradlew -p examples/spring-boot bootRunLocal -Pmanaged=true --args=--example.fail=true
```

The second command intentionally fails. Its managed instance is still removed.

To debug, run `./gradlew -p examples/spring-boot bootRunLocalDebug`, then attach a debugger to `localhost:5005`. The JVM waits for the debugger by default. Configure `springBootEmulator.debugPort` and `debugSuspend` to change that behavior.

The application chooses Spring Boot 4.1.2 in [build.gradle.kts](build.gradle.kts). The companion does not choose or apply a Boot version. See [Application.java](src/main/java/example/Application.java) for the SDK round trip and the [Spring Boot guide](../../docs/src/docs/asciidoc/spring-boot.adoc) for Kotlin/Groovy configuration.
