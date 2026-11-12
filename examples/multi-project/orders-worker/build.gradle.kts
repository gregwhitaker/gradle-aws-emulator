import io.github.gregwhitaker.awsemulator.model.EmulatorMode

plugins {
    application
    id("io.github.gregwhitaker.aws-emulator")
}

repositories { mavenCentral() }
dependencies {
    implementation(platform("software.amazon.awssdk:bom:2.39.6"))
    implementation("software.amazon.awssdk:sqs") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
application { mainClass.set("example.orders.Worker") }
tasks.test {
    useJUnitPlatform()
    systemProperty("example.failTest", providers.gradleProperty("failTest").getOrElse("false"))
}
tasks.named<JavaExec>("run") {
    // The finite publisher exits before the worker consumes its message. Sharing alone does not order tasks.
    dependsOn(":orders-api:run")
    systemProperty("example.fail", providers.gradleProperty("failExample").getOrElse("false"))
}

val applicationMode = providers.gradleProperty("development").map {
    if (it.toBoolean()) EmulatorMode.DEVELOPMENT else EmulatorMode.MANAGED
}.orElse(EmulatorMode.MANAGED)
awsEmulator {
    useSharedRuntime(":local-aws", "application")
    useWith(tasks.named<JavaExec>("run"), applicationMode)
    // Tests always use managed mode, including when applications use development mode.
    useWith(tasks.named<Test>("test"))
}
