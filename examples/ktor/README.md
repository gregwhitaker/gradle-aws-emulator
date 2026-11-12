# Ktor example

This Kotlin application starts a Ktor Netty server on a loopback port, calls its `/message` route, and shuts down. The route sends and receives an SQS message over signed HTTPS using AWS SDK for Java v2. A JUnit 5 test exercises the same route through Ktor's `testApplication` engine and the real emulator.

From the repository root, with Java 17+ and a running Docker engine:

```sh
./gradlew -p examples/ktor test run --configuration-cache
./gradlew -p examples/ktor test run --configuration-cache
```

The core emulator plugin provisions SQS before the consumers start and passes the connection to their JVMs. Both tasks share a fresh managed emulator in each build. The second invocation reuses the configuration cache while rerunning provisioning and both consumers. Managed containers and connection files are removed after success or failure.

For a persistent development emulator:

```sh
./gradlew -p examples/ktor run -Pdevelopment=true --configuration-cache
./gradlew -p examples/ktor run -Pdevelopment=true --configuration-cache
./gradlew -p examples/ktor listSqsQueues
./gradlew -p examples/ktor stopAwsEmulator
```

The server still exits after its HTTP/SQS round trip. `-Pdevelopment=true` keeps the emulator available and affects only `run` and its setup task; `test` always uses a separate managed instance. An application failure also leaves the development instance running until explicitly stopped.

Debug with `./gradlew -p examples/ktor run --debug-jvm`, then attach to port 5005. The JVM waits for the debugger. These commands deliberately fail after the SDK round trip to demonstrate cleanup:

```sh
./gradlew -p examples/ktor run -PfailExample=true
./gradlew -p examples/ktor test -PfailExample=true
```

For a Docker-free compilation check, run `./gradlew -p examples/ktor classes testClasses --configuration-cache`. Running `test` or `build` in this example requires Docker.

The example chooses Ktor 3.6.1 and Kotlin 2.4.21. Use `-PktorVersion=3.5.2` for the other compatibility fixture; the Ktor plugin supplies matching library versions. It uses standard `run` and `test` tasks with `awsEmulator.useWith(...)`. No Ktor-specific emulator companion is necessary.

The AWS client uses the normal SDK endpoint and credentials providers and closes when the Ktor application stops. Blocking SDK calls run on `Dispatchers.IO`. See [build.gradle.kts](build.gradle.kts), [Application.kt](src/main/kotlin/example/Application.kt), [ApplicationTest.kt](src/test/kotlin/example/ApplicationTest.kt), and the [framework guide](../../docs/src/docs/asciidoc/frameworks.adoc). Support here covers JVM execution and tests with AWS SDK for Java v2; AWS SDK for Kotlin, native/multiplatform execution, and automatic reload are outside the verified scope.
