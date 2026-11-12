import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
import io.github.gregwhitaker.awsemulator.aws.ses.*
import io.github.gregwhitaker.awsemulator.aws.sns.CreateSnsTopicWithSqsEndpointTask
import io.github.gregwhitaker.awsemulator.model.EmulatorMode

plugins { java; id("io.github.gregwhitaker.aws-emulator") }
repositories { mavenCentral() }

val integration = sourceSets.create("integration")
dependencies {
    "integrationImplementation"(platform("software.amazon.awssdk:bom:2.39.6"))
    for (service in listOf("sesv2", "sqs", "url-connection-client")) {
        "integrationImplementation"("software.amazon.awssdk:$service")
    }
    "integrationImplementation"("com.fasterxml.jackson.core:jackson-databind:2.18.6")
    "integrationImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "integrationImplementation"("org.junit.jupiter:junit-jupiter")
    "integrationRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val development = providers.gradleProperty("development").map(String::toBoolean).getOrElse(false)
val runtimeMode = if (development) EmulatorMode.DEVELOPMENT else EmulatorMode.MANAGED
if (development) tasks.withType<AbstractAwsResourceTask>().configureEach { mode.set(runtimeMode) }

val sender = tasks.register<CreateSesEmailIdentityTask>("createMailSender") {
    emailIdentity.set("sender@example.test")
}
val template = tasks.register<PutSesEmailTemplateTask>("putWelcomeTemplate") {
    templateName.set("example-welcome")
    subject.set("Welcome {{name}}")
    textBody.set(providers.fileContents(layout.projectDirectory.file("fixtures/welcome.txt")).asText)
    htmlBody.set(providers.fileContents(layout.projectDirectory.file("fixtures/welcome.html")).asText)
    overwrite.set(providers.gradleProperty("overwrite").map(String::toBoolean).orElse(false))
}
val configuration = tasks.register<CreateSesConfigurationSetTask>("createMailConfiguration") {
    configurationSetName.set("example-mail")
}
val topic = tasks.register<CreateSnsTopicWithSqsEndpointTask>("createMailEvents") {
    topicName.set("example-mail-events")
    queueName.set("example-mail-events")
    subscriptionAttributes.put("RawMessageDelivery", "true")
}
val events = tasks.register<PutSesSnsEventDestinationTask>("configureMailEvents") {
    dependsOn(configuration, topic)
    configurationSetName.set("example-mail")
    eventDestinationName.set("sns-events")
    // The default emulator credentials use account 000000000000; this example fixes us-east-1 below.
    topicArn.set("arn:aws:sns:us-east-1:000000000000:example-mail-events")
}
val provision = tasks.register("provisionSes") { dependsOn(sender, template, events) }
val send = tasks.register<SendSesEmailTask>("sendFixtureEmail") {
    dependsOn(provision)
    fromAddress.set("sender@example.test")
    toAddresses.add("success@simulator.amazonses.com")
    subject.set("Local receipt")
    textBody.set("Order 42 was received.")
    htmlBody.set("<p>Order <b>42</b> was received.</p>")
    configurationSetName.set("example-mail")
}
val raw = tasks.register<SendSesEmailTask>("sendRawFixtureEmail") {
    dependsOn(provision)
    fromAddress.set("sender@example.test")
    toAddresses.add("success@simulator.amazonses.com")
    rawMessage.set(layout.projectDirectory.file("fixtures/message.eml"))
}
val insights = tasks.register<GetSesMessageInsightsTask>("inspectFixtureEmail") {
    dependsOn(send)
    messageId.set(providers.fileContents(send.flatMap { it.messageIdFile }).asText.map { it.trim() })
    outputFile.set(layout.buildDirectory.file("ses/insights.json"))
}
val integrationTest = tasks.register<Test>("integrationTest") {
    dependsOn(insights, raw)
    testClassesDirs = integration.output.classesDirs
    classpath = integration.runtimeClasspath
    useJUnitPlatform()
    systemProperty("example.insights", insights.flatMap { it.outputFile }.get().asFile.absolutePath)
    systemProperty("example.rawMessageId", raw.flatMap { it.messageIdFile }.get().asFile.absolutePath)
    systemProperty("example.fail", providers.gradleProperty("failExample").getOrElse("false"))
}
awsEmulator { port.set(0); region.set("us-east-1"); useWith(integrationTest, runtimeMode) }
