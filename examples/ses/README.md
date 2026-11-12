# SES email example

Provision local SES identities, stored templates, and configuration sets; submit simple/raw emails with tasks and the application SDK; inspect accepted-message metadata; and consume simulated send/bounce events through SNS and SQS.

Requires Java 17+, Docker with Linux containers, and the repository's Gradle wrapper. No AWS account or AWS CLI is needed. Run from the repository root:

```sh
./gradlew -p examples/ses integrationTest --configuration-cache
./gradlew -p examples/ses integrationTest --configuration-cache
```

The second invocation reuses the configuration cache and sends new messages against a new managed instance. Both runs remove their containers. To demonstrate cleanup after a failing consumer:

```sh
./gradlew -p examples/ses integrationTest -PfailExample=true --configuration-cache
```

This command intentionally fails its test; the managed container is still removed.

## Persistent development

```sh
./gradlew -p examples/ses provisionSes -Pdevelopment=true
./gradlew -p examples/ses listSesEmailIdentities listSesConfigurationSets
./gradlew -p examples/ses inspectFixtureEmail -Pdevelopment=true
./gradlew -p examples/ses stopAwsEmulator
```

The send task writes its ID to `build/aws-emulator/ses/sendFixtureEmail/message-id.txt`; the inspection task writes `build/ses/insights.json`. These files contain metadata, not a retrievable mailbox or sent message body. Send tasks run each time, even when output files already exist.

The example's topic ARN uses the default emulator account `000000000000` and the explicitly selected `us-east-1` region. `provisionSes` reuses existing identities, configuration sets, and matching templates/event destinations. Different template content requires `-Poverwrite=true`; the configuration-set task preserves existing options. Stop/restart discards all server state.

## MailHog, Mailpit, and template limitations

AWS Emulator **1.0.322 does not forward SES sends to an SMTP server**. There is no MailHog/Mailpit relay setting in this plugin. `SendEmail` records an accepted message and can publish simulated events; it does not send mail externally. `GetMessageInsights` exposes subject, sender, recipients, and events, but no body. An SNS `Delivery` event is simulated, not proof that an inbox received email.

For viewing rendered HTML, MIME, or attachments in [MailHog](https://github.com/mailhog/MailHog) or [Mailpit](https://mailpit.axllent.org/docs/configuration/smtp/), configure your application's **SMTP mail sender** to use the local SMTP sink (typically `localhost:1025`). An AWS SES SDK client speaks the SES HTTPS API and cannot connect directly to an SMTP port. Applications that normally use the SES API need a local SMTP mail-sender implementation/profile, or a separate SES-to-SMTP adapter. Neither relay nor SMTP-container management is implemented here.

The pinned emulator supports template create/get/update/delete, but SES v2 `SendEmail` with template content, `TestRenderEmailTemplate`, and `ListEmailTemplates` return `501 NotImplemented`. This example provisions and reads back templates but sends explicit simple/raw content. Test application-rendered bodies separately; this example checks SES acceptance and simulated event wiring.

See [build.gradle.kts](build.gradle.kts), [the SDK integration test](src/integration/java/example/SesIntegrationTest.java), and the [SES guide](../../docs/src/docs/asciidoc/ses.adoc).
