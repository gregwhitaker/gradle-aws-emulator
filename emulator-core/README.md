# AWS Emulator Core

Shared Java 17 support used by the Gradle plugin and the standalone Testcontainers library. This module has no Gradle, Testcontainers, JUnit, or Docker dependency in its production API.

Published coordinates: `io.github.gregwhitaker:aws-emulator-core:0.2.0` (unreleased).

- `EmulatorImage`: pinned Dockerfile generation and daemon architecture mapping.
- `EmulatorCredentials`: reads an explicit generated credentials file using AWS SDK v2, with sanitized failures and no provider-chain fallback.
- `EmulatorTls`: loads emulator trust managers without modifying global TLS configuration.
- `EmulatorHealth`: verifies HTTPS health responses with certificate and hostname validation.

Container ownership, image building, file copying, cleanup, and consumer process configuration belong to the adapters. The Gradle plugin retains adapters that preserve its existing exception types and runtime behavior.

Run `./gradlew :emulator-core:test` for unit checks and `./gradlew :emulator-core:publishToMavenLocal` to make the shared artifact available locally.
