# Named runtimes

Run two suites against independent emulator instances in one project. Both use the same SQS queue name and reusable resource set; each receives its own endpoint, credentials, region, and state. The optional `remote` runtime connects to an existing emulator.

Run from the repository root with Java 17+ and a local Docker engine:

```sh
./gradlew -p examples/named-runtimes integrationTest acceptanceTest --configuration-cache
./gradlew -p examples/named-runtimes integrationTest acceptanceTest --configuration-cache
```

The suites send and receive their own messages and verify empty initial queues. The second invocation reuses the configuration cache with fresh managed instances. Both instances are removed after the participating tasks finish. `integration` inherits `us-east-1`; `acceptance` uses `eu-west-1`. Connection summaries are written under `build/connections/` without credential contents.

To run with Gradle Isolated Projects, add `-Dorg.gradle.unsafe.isolated-projects=true` to both commands above. Each named runtime keeps its independent state and cleanup, including when the configuration cache is reused. Gradle 9.7+ uses `--isolated-projects` instead.

To exercise cleanup after a failed suite:

```sh
./gradlew -p examples/named-runtimes integrationTest acceptanceTest -PfailSuite=acceptance --continue
```

This command intentionally fails; both managed instances are still cleaned up.

## Separate development instances

Seed both persistent instances, then inspect or reset one:

```sh
./gradlew -p examples/named-runtimes seedAwsEmulatorIntegration seedAwsEmulatorAcceptance --configuration-cache
./gradlew -p examples/named-runtimes listSqsQueues --runtime acceptance
./gradlew -p examples/named-runtimes resetAwsEmulatorIntegration
./gradlew -p examples/named-runtimes statusAwsEmulatorAcceptance
./gradlew -p examples/named-runtimes stopAwsEmulatorIntegration stopAwsEmulatorAcceptance
```

Resetting `integration` discards and recreates only its development state. `acceptance` remains running with its resources intact. These development instances are separate from the suites' managed instances. Runtime files live under `.aws-emulator/runtimes/<name>/`.

## Optional external runtime

Supply an existing emulator's HTTPS endpoint, credentials file, and PEM CA certificate. File arguments can be absolute paths or paths relative to this example directory:

```sh
./gradlew -p examples/named-runtimes integrationTest acceptanceTest remoteTest \
  -PremoteEndpoint=https://localhost:9297 \
  -PremoteCredentialsFile=/path/to/emulator_credentials \
  -PremoteCertificateFile=/path/to/ca.pem \
  --configuration-cache
```

The managed suites remain isolated while `remoteTest` provisions its queue on the external endpoint. The external emulator and its resources remain after success or failure. Connection properties are unnecessary when `remoteTest` is not selected. No Docker is needed when running `remoteTest` alone.

See [build.gradle.kts](build.gradle.kts), [the SDK test](src/integration/java/example/NamedRuntimeTest.java), and the [named runtime guide](../../docs/src/docs/asciidoc/named-runtimes.adoc).

## Containerized CI and remote Docker

Run the managed suites through Docker's control connection when the runner and engine have different network namespaces or filesystems:

```sh
./gradlew -p examples/named-runtimes integrationTest acceptanceTest -PdockerTunnel=true --configuration-cache
```

Add `-PdockerContext=ci` or `-PdockerHost=ssh://builder@example.com` to select the engine explicitly; otherwise Docker uses its existing environment/context. Both named managed runtimes inherit these settings. The external `remote` runtime keeps its existing connection and lifecycle. Repeat the command to verify configuration-cache reuse. Tunnel mode supports managed suites; omit it for persistent development scenarios. See the [remote Docker guide](../../docs/src/docs/asciidoc/remote-docker.adoc).
