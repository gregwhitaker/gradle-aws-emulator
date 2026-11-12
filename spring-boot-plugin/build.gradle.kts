/*
 * Copyright 2026 Greg Whitaker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.adarshr.gradle.testlogger.theme.ThemeType
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.adarshr.test-logger")
    id("io.github.gmazzo.gradle.testkit.jacoco")
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "io.github.gregwhitaker"
version = providers.gradleProperty("pluginVersion").get()
description = "Spring Boot development and debugging with AWS Emulator"
base { archivesName.set("gradle-aws-emulator-spring-boot") }
val projectWebsite = "https://github.com/gregwhitaker/gradle-aws-emulator"

repositories { mavenCentral() }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<Jar>().configureEach {
    from(rootProject.layout.projectDirectory.file("LICENSE")) { into("META-INF") }
}

val functionalSourceSet = sourceSets.create("functional")
val integrationSourceSet = sourceSets.create("integration")
for (testSourceSet in listOf(functionalSourceSet, integrationSourceSet)) {
    configurations[testSourceSet.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
    configurations[testSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    implementation(project(":plugin"))
    // The consuming application supplies Spring Boot. Its classes are resolved from that plugin's loader.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(project(":plugin"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    for (testSourceSet in listOf(functionalSourceSet, integrationSourceSet)) {
        add(testSourceSet.implementationConfigurationName, project(":spring-boot-plugin"))
        add(testSourceSet.implementationConfigurationName, testFixtures(project(":plugin")))
        add(testSourceSet.implementationConfigurationName, gradleTestKit())
    }
}

gradlePlugin {
    website.set(projectWebsite)
    vcsUrl.set("$projectWebsite.git")
    testSourceSets(functionalSourceSet, integrationSourceSet)
    plugins {
        create("awsEmulatorSpringBoot") {
            id = "io.github.gregwhitaker.aws-emulator.spring-boot"
            implementationClass = "io.github.gregwhitaker.awsemulator.boot.AwsEmulatorSpringBootPlugin"
            displayName = "AWS Emulator Spring Boot Gradle Plugin"
            description = project.description
            tags.set(listOf("aws", "aws-emulator", "spring-boot", "docker", "development"))
            compatibility { features { configurationCache = true } }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") artifactId = "gradle-aws-emulator-spring-boot"
        pom {
            name.set("AWS Emulator Spring Boot Gradle Plugin")
            description.set(project.description)
            url.set(projectWebsite)
            scm {
                url.set(projectWebsite)
                connection.set("scm:git:$projectWebsite.git")
                developerConnection.set("scm:git:git@github.com:gregwhitaker/gradle-aws-emulator.git")
            }
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
        }
    }
}

testlogger {
    theme = ThemeType.MOCHA
    showSimpleNames = true
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("test.gradle.version", providers.gradleProperty("testGradleVersion").getOrElse(""))
    systemProperty("test.gradle.installation", providers.gradleProperty("testGradleInstallation").getOrElse(""))
}
val functionalTest = tasks.register<Test>("functionalTest") {
    group = "verification"
    description = "Verifies Spring Boot compatibility without Docker."
    testClassesDirs = functionalSourceSet.output.classesDirs
    classpath = functionalSourceSet.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}
tasks.register<Test>("integrationTest") {
    systemProperty("cognito.example", rootProject.layout.projectDirectory.dir("examples/cognito").asFile.absolutePath)
    inputs.files(rootProject.fileTree("examples/cognito") {
        include("build.gradle.kts", "src/**", "fixtures/**")
    }).withPropertyName("cognitoExample").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("springCloudAws.example", rootProject.layout.projectDirectory.dir("examples/spring-cloud-aws").asFile.absolutePath)
    inputs.files(rootProject.fileTree("examples/spring-cloud-aws") {
        include("build.gradle.kts", "src/**", "fixtures/**")
    }).withPropertyName("springCloudAwsExample").withPathSensitivity(PathSensitivity.RELATIVE)
    group = "verification"
    description = "Runs Spring Boot consumers against a real AWS Emulator."
    testClassesDirs = integrationSourceSet.output.classesDirs
    classpath = integrationSourceSet.runtimeClasspath
    shouldRunAfter(functionalTest)
}
apply(from = rootProject.file("gradle/coverage.gradle.kts"))

tasks.named("check") { dependsOn(functionalTest) }
tasks.named("publishPlugins") { dependsOn(tasks.named("check")) }
tasks.withType<Javadoc>().configureEach {
    exclude("**/internal/**")
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        setNoTimestamp(true)
        encoding = "UTF-8"
        docEncoding = "UTF-8"
    }
}
