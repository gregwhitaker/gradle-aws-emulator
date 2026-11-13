# Existing emulator connection

This example provisions an SQS queue and Kinesis stream on an already-running AWS Emulator, then runs a signed SDK request from a JUnit test. The plugin never starts, stops, resets, or removes that emulator. Its resources remain after the build.

Use Java 17+, the repository's Gradle wrapper, and an existing emulator's HTTPS endpoint, credentials file, and PEM CA certificate. Docker is unnecessary for this consumer build.

From the repository root:

```sh
./gradlew -p examples/external integrationTest --configuration-cache \
  -PemulatorEndpoint=https://127.0.0.1:9297 \
  -PemulatorCredentialsFile=/absolute/path/to/emulator_credentials \
  -PemulatorCertificateFile=/absolute/path/to/ca.pem
```

Replace the endpoint and paths with your connection. Use `-PemulatorRegion=...` if the emulator uses a region other than `us-east-1`. Repeat the command to verify configuration-cache reuse.

To try it using a separately owned development instance from this checkout:

```sh
./gradlew -p examples/basic startAwsEmulator -PawsEmulator.port=9297
./gradlew -p examples/external integrationTest --configuration-cache \
  -PemulatorEndpoint=https://127.0.0.1:9297 \
  "-PemulatorCredentialsFile=$PWD/examples/basic/.aws-emulator/development/.aws/emulator/9297/emulator_credentials" \
  "-PemulatorCertificateFile=$PWD/examples/basic/.aws-emulator/development/.aws/emulator/9297/ca.pem"
./gradlew -p examples/basic statusAwsEmulator
```

The basic instance and its resources remain alive. When finished, stop it through its owner:

```sh
./gradlew -p examples/basic stopAwsEmulator
```

Use the same external connection properties with `seedAwsEmulator`, `statusAwsEmulator`, or `exportAwsEmulatorConnection`. Lifecycle commands and scenario resets are rejected in this example. Adding `-PfailExample=true` deliberately fails the consumer test; the external emulator remains running.

The [build script](build.gradle.kts) supplies the connection through `awsEmulator.external`. The [test](src/integration/java/example/ExternalEmulatorTest.java) uses normal SDK credential and endpoint providers. Keep source credentials and generated `.aws-emulator/` files out of version control. See the [external connection guide](../../docs/src/docs/asciidoc/external-emulators.adoc) for the full behavior.
