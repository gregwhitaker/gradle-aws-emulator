# Spring Cloud AWS

This example uses **Spring Cloud AWS 3.4.0 and Spring Boot 3.5.16**, following the framework's [compatibility matrix](https://github.com/awspring/spring-cloud-aws#compatibility-with-spring-project-versions). Java 17+, the repository's Gradle wrapper, and a running Docker engine are required. No AWS account or CLI is needed.

From the repository root:

```shell
./gradlew -p examples/spring-cloud-aws test bootRunLocal --configuration-cache
./gradlew -p examples/spring-cloud-aws test bootRunLocal --configuration-cache
./gradlew -p examples/spring-cloud-aws stopAwsEmulator
```

The application exits after verifying its integrations. `test` uses a fresh managed emulator and removes it afterwards; `bootRunLocal` uses a separate development emulator that survives application exit. Both use the `application` resource set declared in [build.gradle.kts](build.gradle.kts), including its message fixture. Repeat the command to exercise configuration-cache reuse. Consumers sharing a mode share resources and run setup only once per build; this example's combined command uses different modes so each listener receives its own fixture.

On a clean checkout, Boot may refresh the initial cache entry when compilation creates class directories. Run the command once more to see cache reuse after that first compilation.

The [local profile](src/main/resources/application-local.properties) declares **required** `aws-parameterstore:` and `aws-secretsmanager:` imports. Setup provisions these before Spring starts. The imported values are injected into [CloudChecks](src/main/java/example/CloudChecks.java), which also uses Spring's auto-configured `SqsAsyncClient` and `SqsTemplate` to send messages. [EventListener](src/main/java/example/EventListener.java) consumes the seeded and newly sent messages with `@SqsListener` and waits for successful manual acknowledgements. All asynchronous waits have deadlines. [ApplicationTest](src/test/java/example/ApplicationTest.java) runs those same checks in a Spring test context and closes its listener afterwards.

The plugin supplies the local endpoint, generated emulator credentials, region, and consumer JVM trust at execution time. The local profile maps the endpoint to Spring Cloud AWS, including the early bootstrap clients used by configuration imports. The SDK's normal credential chain reads the generated credential file. The example supplies no custom client beans or trust managers, and certificate and hostname validation remain enabled. Endpoint and import properties are documented in the [Spring Cloud AWS reference](https://docs.awspring.io/spring-cloud-aws/docs/3.4.0/reference/html/index.html).

For a managed application run or intentional failures:

```shell
./gradlew -p examples/spring-cloud-aws bootRunLocal -Pmanaged=true --configuration-cache
./gradlew -p examples/spring-cloud-aws bootRunLocal -Pmanaged=true --args=fail
./gradlew -p examples/spring-cloud-aws test -PfailExample=true
```

The last two commands fail after verifying the integrations; managed containers and connection files are still removed. `bootRunLocalDebug` supports the same resource set and waits for a debugger on port 5005 by default.

The same definitions can also be loaded as a development scenario:

```shell
./gradlew -p examples/spring-cloud-aws resetAwsEmulator --configuration-cache
./gradlew -p examples/spring-cloud-aws stopAwsEmulator
```

Reset discards all resources in this project's development emulator before applying the set. Spring Cloud AWS integration is tested against the pinned 3.x pairing above; the separate [Spring Boot example](../spring-boot/README.md) covers Boot 4 with direct SDK clients.
