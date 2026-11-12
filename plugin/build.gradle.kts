import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    `java-gradle-plugin`
    `java-test-fixtures`
    `maven-publish`
}

group = "io.github.gregwhitaker"
version = providers.gradleProperty("pluginVersion").get()
description = "Gradle lifecycle, resource provisioning, and test integration for AWS Emulator"
base { archivesName.set("gradle-aws-emulator") }

repositories { mavenCentral() }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Jar>().configureEach {
    from(rootProject.layout.projectDirectory.file("LICENSE")) { into("META-INF") }
}

val functionalSourceSet = sourceSets.create("functional")
val integrationSourceSet = sourceSets.create("integration")

for (testSourceSet in listOf(functionalSourceSet, integrationSourceSet)) {
    configurations[testSourceSet.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
    configurations[testSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") artifactId = "gradle-aws-emulator"
        pom {
            name.set("AWS Emulator Gradle Plugin")
            description.set(project.description)
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    implementation(platform("software.amazon.awssdk:bom:2.39.6"))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:sqs") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testFixturesImplementation(gradleTestKit())
    for (testSourceSet in listOf(functionalSourceSet, integrationSourceSet)) {
        add(testSourceSet.implementationConfigurationName, project(":plugin"))
        add(testSourceSet.implementationConfigurationName, testFixtures(project(":plugin")))
        add(testSourceSet.implementationConfigurationName, gradleTestKit())
    }
}

gradlePlugin {
    testSourceSets(functionalSourceSet, integrationSourceSet)
    plugins {
        create("awsEmulator") {
            id = "io.github.gregwhitaker.aws-emulator"
            implementationClass = "io.github.gregwhitaker.awsemulator.AwsEmulatorPlugin"
            displayName = "AWS Emulator Gradle Plugin"
            description = project.description
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("test.gradle.version", providers.gradleProperty("testGradleVersion").getOrElse(""))
    systemProperty("test.gradle.installation", providers.gradleProperty("testGradleInstallation").getOrElse(""))
    testLogging { events("failed", "skipped") }
}

val functionalTest = tasks.register<Test>("functionalTest") {
    description = "Runs Gradle TestKit tests without Docker."
    group = "verification"
    testClassesDirs = functionalSourceSet.output.classesDirs
    classpath = functionalSourceSet.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against a real Docker daemon and AWS Emulator."
    group = "verification"
    testClassesDirs = integrationSourceSet.output.classesDirs
    classpath = integrationSourceSet.runtimeClasspath
    shouldRunAfter(tasks.named("test"), functionalTest)
}

tasks.named("check") { dependsOn(functionalTest) }

val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

tasks.withType<Javadoc>().configureEach {
    // The published reference documents the supported API, not implementation internals.
    exclude("**/internal/**")
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        setNoTimestamp(true)
        encoding = "UTF-8"
        docEncoding = "UTF-8"
    }
}
