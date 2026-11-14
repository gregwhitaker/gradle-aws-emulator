# AWS Emulator for Testcontainers

A standalone Java 17+ library for running AWS's [AWS Emulator](https://github.com/aws/aws-emulator) in tests. It uses Testcontainers 2.0.5 and has no Gradle API or JUnit dependency. Use it with JUnit's Testcontainers extension, another test framework, or explicit `start()` / `close()` calls.

Version **0.2.0** is unreleased. Run the [checkout example](../examples/testcontainers/README.md), or publish the libraries to your local Maven repository:

```sh
./gradlew :emulator-core:publishToMavenLocal :testcontainers:publishToMavenLocal
```

Then use `mavenLocal()` and `mavenCentral()` in the consumer and add:

```kotlin
testImplementation("io.github.gregwhitaker:testcontainers-aws-emulator:0.2.0")
testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
testImplementation("software.amazon.awssdk:s3:2.39.6")
```

Keep your JUnit Jupiter dependency and launcher. The JUnit extension is optional and separate from this library.

```java
@Testcontainers
class StorageTest {
    @Container
    static final AwsEmulatorContainer aws = new AwsEmulatorContainer();

    @Test
    void createsBucket() {
        try (var s3 = S3Client.builder()
                .endpointOverride(aws.getEndpoint())
                .region(Region.of(aws.getRegion()))
                .credentialsProvider(aws.getCredentialsProvider())
                .httpClientBuilder(aws.getHttpClientBuilder())
                .forcePathStyle(true)
                .build()) {
            s3.createBucket(request -> request.bucket("example-bucket"));
        }
    }
}
```

Import `io.github.gregwhitaker.awsemulator.testcontainers.AwsEmulatorContainer`. The [complete example](../examples/testcontainers/src/test/java/example/StandaloneEmulatorTest.java) includes all imports and dependencies.

## Configuration

| API | Behavior |
| --- | --- |
| `new AwsEmulatorContainer()` | Builds AWS Emulator **1.0.322**, using the shared image definition. |
| `new AwsEmulatorContainer("1.0.322")` | Builds the specified pinned release. |
| `new AwsEmulatorContainer(DockerImageName.parse("my-emulator:tag"))` | Uses a compatible image with an `aws-emulator --bind 0.0.0.0` entrypoint, the `cat` utility, and UID 1000 (or root). |
| `withRegion("eu-west-1")` | Sets the SDK region; default `us-east-1`. |
| `withTelemetryEnabled(true)` | Enables upstream usage telemetry; disabled by default. |
| `withStartupTimeout(Duration.ofSeconds(180))` | Changes the default 120-second readiness timeout, excluding image preparation. |
| `getEndpoint()` | The mapped HTTPS endpoint on the Docker host. |
| `getCredentialsProvider()` | Explicit generated credentials for AWS SDK v2. |
| `getHttpClientBuilder()` | A new synchronous SDK HTTP builder with emulator trust and normal hostname verification. |
| `getCredentialsFile()` / `getCertificateFile()` | Copied credentials and PEM CA for other clients; valid until stop. |

Configure region and telemetry before starting. Construction does not contact Docker. Default image builds select AMD64 or ARM64 using the Docker daemon's architecture, and cache the image between runs. Containers use a random mapped host port, internal port 9297, authentication enabled, and a 2 GiB memory limit. Standard Testcontainers Docker settings apply.

Generated files live in a 16 MiB in-memory `/home/emulator` directory owned by UID 1000. A custom image using another UID must override the mount options through Testcontainers' `withTmpFs(...)`.

Readiness requires generated connection files and a successful, certificate-verified HTTPS health request. Files are copied through the Docker API into a private temporary directory, so no host bind mount is needed. Startup failure and normal stop remove the container and copied files. Calling `start()` after `stop()` starts with fresh AWS state. Persistent `withReuse(true)` is unsupported.

## SDK and TLS behavior

The library does not change environment variables, system properties, the JVM's default SSL context, or any default credential provider. Explicitly configure each SDK client as above. The HTTP builder trusts this emulator only; use it for emulator clients. The SDK client owns and closes the HTTP client created from that builder.

Use S3 path-style access. For operations using SDK host prefixes, also configure `SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION` through the client's override configuration, as shown in the [connection guide](../docs/src/docs/asciidoc/connections.adoc).

The endpoint host must be reachable from the test JVM and covered by the emulator's certificate. Local Docker environments using `localhost` or `127.0.0.1` are the intended default. Remote Docker hosts, custom network aliases, and applications running in other containers may need additional routing and valid certificates; this library does not bypass verification or automatically configure those applications. Async SDK clients can use the exported CA with their own HTTP transport.

## Relationship to the Gradle plugin

This library owns its containers through Testcontainers. The Gradle plugin continues to own its existing managed and persistent development lifecycles. Both consume `aws-emulator-core` for image generation, credential parsing, TLS trust, and readiness checks. The plugin does not depend on Testcontainers.

Provision resources in Java test setup when Testcontainers owns the emulator. A container started by JUnit is not available to preceding Gradle resource tasks. To use the plugin's external connection mode, the container and its exported files must already exist before those tasks execute, and its owner must keep it alive.

## Verification

```sh
./gradlew :emulator-core:test :testcontainers:test
./gradlew :testcontainers:integrationTest
```

Unit tests require no Docker. Integration tests use real signed AWS calls and cover TLS isolation, multiple containers, restart, startup failure, and cleanup.
