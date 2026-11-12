# Shared runtime across projects

Three Gradle subprojects demonstrate one emulator and one resource setup shared by two application modules:

- `local-aws` owns the runtime settings, `application` resource set, and development scenario.
- `orders-api` publishes an order to the shared SQS queue.
- `orders-worker` receives and deletes that order. Its `run` task depends on `:orders-api:run` so the finite publisher completes first.

Both applications select the same owner and resources:

```kotlin
awsEmulator {
    useSharedRuntime(":local-aws", "application")
    useWith(tasks.named<JavaExec>("run"), applicationMode)
    useWith(tasks.named<Test>("test"))
}
```

The applications use plain Java and AWS SDK for Java v2. They exit after one exchange, so no HTTP server or manual shutdown is needed to run the example. Each module also has a JUnit test that accesses the provisioned queue without consuming the application's messages.

## Run

From the repository root, with Java 17+ and Docker running Linux containers:

```sh
./gradlew -p examples/multi-project verifySharedRuntime --parallel --configuration-cache
./gradlew -p examples/multi-project verifySharedRuntime --parallel --configuration-cache
```

This independent composite build consumes the plugin from the checkout. The first run may build the emulator image. The second reuses the configuration cache. All four JVM consumers and the owner resource task use one managed emulator. Expect `Published order-1001 to the shared queue` followed by `Processed order-1001 from the other module`. The plugin removes the container and generated connection files after all consumers finish.

To demonstrate cleanup after an application or test failure:

```sh
./gradlew -p examples/multi-project :orders-worker:run -PfailExample=true
./gradlew -p examples/multi-project :orders-worker:test -PfailTest=true
```

These commands intentionally fail. Their managed emulator is still removed.

## Persistent development

```sh
./gradlew -p examples/multi-project :orders-worker:run -Pdevelopment=true --configuration-cache
./gradlew -p examples/multi-project :local-aws:statusAwsEmulator
./gradlew -p examples/multi-project verifySharedRuntime -Pdevelopment=true --parallel --configuration-cache
./gradlew -p examples/multi-project :local-aws:stopAwsEmulator
```

Application runs share the owner's persistent development instance. Tests still use a separate managed instance, and their cleanup leaves development running. Resource provisioning runs again each invocation, reusing the existing queue. The owner uses `port = 0` so managed and development instances can coexist.

Use `:local-aws:resetAwsEmulator` to discard the development state and recreate the selected scenario. Reset and stop affect every application using that shared development instance. Run them when those applications are no longer using it.

## Ownership and Spring Boot

The root build declares the core plugin with `apply false` so all sharing projects use the same plugin classes. Keep this declaration when mixing core and Spring Boot companion plugins. Configure emulator version, image, port, region, timeouts, and working directory in `local-aws`. Those runtime settings in consumer projects are ignored after selecting a shared owner. The default working directory is `local-aws/.aws-emulator`; application projects do not create their own runtime files.

The same `useSharedRuntime(":local-aws", "application")` call works with the Spring Boot companion. Its `bootRunLocal` and `bootRunLocalDebug` tasks automatically receive the owner resources in their selected mode. Long-running Boot applications in different projects can run with `--parallel`; configure separate application HTTP and debug ports. Selecting a shared runtime does not start ordinary `bootRun` or opt tests in automatically.

Sharing is explicit and limited to projects in the same Gradle build. Omit the resource-set names to share only the runtime. Separate Gradle invocations get separate managed instances; development invocations can reuse the owner's persistent instance. Sharing does not order application tasks or isolate their resource data.
