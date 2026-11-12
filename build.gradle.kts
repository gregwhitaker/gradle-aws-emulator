plugins { base }

description = "Build, verification, and documentation for the AWS Emulator Gradle plugin"

tasks.named("assemble") { dependsOn(":plugin:assemble", ":spring-boot-plugin:assemble", ":docs:assemble") }
tasks.named("check") { dependsOn(":plugin:check", ":spring-boot-plugin:check", ":docs:check") }
tasks.named("clean") { dependsOn(":plugin:clean", ":spring-boot-plugin:clean", ":docs:clean") }

// Preserve the 0.1 command while keeping real-emulator tests in their own source set.
tasks.register("dockerTest") {
    group = "verification"
    description = "Runs the plugin integration suite against a real Docker daemon."
    dependsOn(":plugin:integrationTest", ":spring-boot-plugin:integrationTest")
}

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "8.14.3"
    distributionSha256Sum = "bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
}
