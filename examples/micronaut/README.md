# Micronaut example

This Java application starts a Micronaut context, obtains an injected SQS client from Micronaut AWS, sends and receives a message over signed HTTPS, and exits. Its `@MicronautTest` uses the same application bean. Gradle provisions the queue before each consumer starts and supplies the endpoint, credentials, region, and TLS trust through the core emulator plugin.

From the repository root, with Java 17+ and a running Docker engine:

```sh
./gradlew -p examples/micronaut test run --configuration-cache
./gradlew -p examples/micronaut test run --configuration-cache
```

Both tasks use the same fresh managed emulator within each build. The second invocation reuses the configuration cache but reruns provisioning, tests, and the application. The managed container and connection files are removed after success or failure.

For a persistent development emulator:

```sh
./gradlew -p examples/micronaut run -Pdevelopment=true --configuration-cache
./gradlew -p examples/micronaut run -Pdevelopment=true --configuration-cache
./gradlew -p examples/micronaut listSqsQueues
./gradlew -p examples/micronaut stopAwsEmulator
```

`-Pdevelopment=true` affects only `run` and its setup task. `test` always uses a separate managed instance. Development instances remain available after application exit or failure until explicitly stopped.

Debug with `./gradlew -p examples/micronaut run --debug-jvm`, then attach to port 5005. The JVM waits for the debugger. To verify failure cleanup, either of these commands deliberately fails after the SDK round trip:

```sh
./gradlew -p examples/micronaut run -PfailExample=true
./gradlew -p examples/micronaut test -PfailExample=true
```

For a Docker-free compilation check, run `./gradlew -p examples/micronaut classes testClasses --configuration-cache`. Running `test` or `build` in this example requires Docker.

The example chooses Micronaut Gradle plugin 4.6.2 and Micronaut platform 4.10.24; use `-PmicronautVersion=4.9.4` for the other compatibility fixture. It uses Micronaut AWS's normal SDK v2 client factory and the URLConnection HTTP client. No framework-specific emulator companion or custom client factory is necessary. The Micronaut Test Resources plugin is not applied; avoid configuring a second AWS/LocalStack provider alongside this emulator. Application-level endpoint overrides or custom credentials beans can override the SDK connection supplied by Gradle.

See [build.gradle.kts](build.gradle.kts), [Application.java](src/main/java/example/Application.java), [Messages.java](src/main/java/example/Messages.java), [MessagesTest.java](src/test/java/example/MessagesTest.java), and the [framework guide](../../docs/src/docs/asciidoc/frameworks.adoc). Support here covers JVM execution and tests with AWS SDK for Java v2; native images and continuous restart workflows are outside the verified scope.
