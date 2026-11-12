# Cognito User Pools and Spring Security

This standalone build uses the checkout's plugins to provision a Cognito User Pool, a public app client, and two confirmed users. The application reads the generated pool/client IDs and issuer/JWKS URLs at startup. Spring Security verifies access tokens over trusted HTTPS and maps `cognito:groups` to application roles.

Run from the repository root with Java 17+ and Docker:

```sh
./gradlew -p examples/cognito test --configuration-cache
./gradlew -p examples/cognito test --configuration-cache
```

The tests sign in with AWS SDK v2, refresh a token, and call the application's `/me` and `/admin` endpoints. They check anonymous rejection, group authorization, and rejection of tampered tokens, ID tokens, tokens for another app client, and incorrect passwords. The second invocation reuses Gradle configuration while provisioning a fresh managed runtime and exporting its new IDs. Managed containers and connection files are removed after success or failure.

To exercise failure cleanup:

```sh
./gradlew -p examples/cognito test -PfailExample=true
```

That command intentionally fails the application test.

For persistent development resources and a local Spring Boot application:

```sh
./gradlew -p examples/cognito seedAwsEmulator
./gradlew -p examples/cognito bootRunLocal
```

The application listens on port 8080; pass `--args=--server.port=8081` to choose another port. Use `bootRunLocalDebug` to wait for a debugger on port 5005. Stop the application with Ctrl+C; the development emulator remains until `./gradlew -p examples/cognito stopAwsEmulator`. `bootRunLocal -PsmokeBoot=true --args=--server.port=0` starts and exits the application for automated verification.

`build/cognito/development.json` contains the current local endpoint, region, pool ID, client ID, issuer, and JWKS URI. Sign in using the same SDK calls in [CognitoIntegrationTest.java](src/test/java/example/CognitoIntegrationTest.java), then send the returned **access token** in `Authorization: Bearer <token>`. `alice` belongs to `admins`; `bob` belongs to `members`. Both have the local-only password in [fixtures/users.json](fixtures/users.json). `/me` accepts either user, and `/admin` accepts only Alice.

The same resource set supplies tests, scenarios, and Boot tasks. Managed tests use `build/cognito/managed.json`; development runs use `build/cognito/development.json`. Read exports only after their setup tasks complete, since old exports no longer describe live resources after an emulator is stopped. Passwords, tokens, and client secrets are not exported.

**Emulator compatibility:** pinned AWS Emulator `1.0.322` signs Cognito JWTs with **ES256**, while AWS Cognito uses **RS256**. [LocalCognitoSecurity.java](src/main/java/example/LocalCognitoSecurity.java) explicitly allows ES256 only in the `local` profile, validates issuer and expiry, and requires `token_use=access` plus this app's `client_id`. TLS and signature verification stay enabled. Configure production Cognito independently with its real issuer and RS256 policy. This example covers password and refresh API flows; it does not implement hosted login, federation, Identity Pools, or MFA.

See the [Cognito guide](../../docs/src/docs/asciidoc/cognito.adoc) for task properties, fixture overwrite behavior, and Kotlin/Groovy configuration.
