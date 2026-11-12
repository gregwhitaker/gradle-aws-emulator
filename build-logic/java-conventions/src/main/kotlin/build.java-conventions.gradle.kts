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

import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    java
    id("build.test-logger")
}

repositories { mavenCentral() }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    // Target Java 17 while using the JDK selected by the developer or CI.
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.withType<Jar>().configureEach {
    from(rootProject.layout.projectDirectory.file("LICENSE")) { into("META-INF") }
}

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

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val functionalSourceSet = sourceSets.create("functional")
val integrationSourceSet = sourceSets.create("integration")

for (testSourceSet in listOf(functionalSourceSet, integrationSourceSet)) {
    configurations[testSourceSet.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
    configurations[testSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = 1
}

val functionalTest = tasks.register<Test>("functionalTest") {
    description = "Runs functional tests without Docker."
    group = "verification"
    testClassesDirs = functionalSourceSet.output.classesDirs
    classpath = functionalSourceSet.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = "verification"
    testClassesDirs = integrationSourceSet.output.classesDirs
    classpath = integrationSourceSet.runtimeClasspath
    shouldRunAfter(tasks.named("test"), functionalTest)
}

tasks.named("check") { dependsOn(functionalTest) }
