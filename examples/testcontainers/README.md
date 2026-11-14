# Standalone Testcontainers example

Run from the repository root with Java 17+ and a Docker environment supported by Testcontainers:

```sh
./gradlew -p examples/testcontainers test --configuration-cache
```

Repeat the command to verify configuration-cache reuse and a fresh emulator. The example uses this checkout through composite dependency substitution; no publishing is needed. It applies only Gradle's Java plugin.

[`StandaloneEmulatorTest`](src/test/java/example/StandaloneEmulatorTest.java) uses JUnit's Testcontainers extension to start AWS Emulator, create an S3 bucket, and round-trip an object over verified HTTPS. JUnit stops the container after the class. You can also import the build into an IDE and run the test with its JUnit runner.

The first run builds the pinned emulator image for the Docker daemon's architecture. Later runs reuse the image, while AWS resources and credentials are fresh. The library uses Docker's API and does not require the `docker` CLI. See the [module README](../../testcontainers/README.md) for connection settings and supported lifecycles.
