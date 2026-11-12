plugins { java; id("io.github.gregwhitaker.aws-emulator") }
repositories { mavenCentral() }
dependencies {
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    testImplementation("org.springframework.security:spring-security-oauth2-jose")
    testImplementation(platform("software.amazon.awssdk:bom:2.39.6"))
    testImplementation("software.amazon.awssdk:cognitoidentityprovider")
    testImplementation("software.amazon.awssdk:url-connection-client")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
awsEmulator { port.set(0); useWith(tasks.test) }
