plugins { java }

repositories { mavenCentral() }

dependencies {
    // Keep the plugin and initializer on the same SDK classpath.
    implementation("io.github.gregwhitaker:gradle-aws-emulator:0.2.0")
    implementation("software.amazon.awssdk:dynamodb:2.39.6")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
