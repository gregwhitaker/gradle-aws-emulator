import io.github.gregwhitaker.awsemulator.aws.cognito.*
import io.github.gregwhitaker.awsemulator.model.EmulatorMode

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.github.gregwhitaker.aws-emulator.spring-boot")
}
repositories { mavenCentral() }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.6")
    implementation(platform("software.amazon.awssdk:bom:2.39.6"))
    implementation("software.amazon.awssdk:cognitoidentityprovider")
    implementation("software.amazon.awssdk:url-connection-client")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val cognito = awsEmulator.resourceSets.register("cognito") {
    provision("pool", CreateCognitoUserPoolTask::class.java) { userPoolName.set("example-users") }
    provision("client", CreateCognitoUserPoolClientTask::class.java) {
        userPoolName.set("example-users"); clientName.set("example-app")
    }
    seed("users", SeedCognitoUsersTask::class.java) {
        userPoolName.set("example-users")
        fixtures.set(layout.projectDirectory.file("fixtures/users.json"))
    }
    seed("configuration", ExportCognitoConfigurationTask::class.java) {
        userPoolName.set("example-users"); clientName.set("example-app")
        outputFile.set(layout.buildDirectory.file(mode.map { "cognito/${it.name.lowercase()}.json" }))
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("example.cognito.configuration", layout.buildDirectory.file("cognito/managed.json").get().asFile.absolutePath)
    systemProperty("example.fail", providers.gradleProperty("failExample").getOrElse("false"))
}
awsEmulator {
    port.set(0)
    useWith(tasks.test, EmulatorMode.MANAGED, cognito)
    scenarios { register("default") { resources(cognito) } }
    springBoot { resourceSets.add(cognito) }
}
tasks.named<JavaExec>("bootRunLocal") {
    systemProperty("example.cognito.configuration", layout.buildDirectory.file("cognito/development.json").get().asFile.absolutePath)
    systemProperty("example.smoke", providers.gradleProperty("smokeBoot").getOrElse("false"))
}
tasks.named<JavaExec>("bootRunLocalDebug") {
    systemProperty("example.cognito.configuration", layout.buildDirectory.file("cognito/development.json").get().asFile.absolutePath)
}
