plugins {
    base
    id("io.github.gregwhitaker.aws-emulator") apply false
}

tasks.register("verifySharedRuntime") {
    group = "verification"
    description = "Runs both modules' tests and sends an order between their application processes."
    dependsOn(":orders-api:test", ":orders-worker:test", ":orders-worker:run")
}
