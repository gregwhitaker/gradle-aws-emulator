import io.github.gregwhitaker.awsemulator.model.EmulatorMode

plugins {
    id("io.github.gregwhitaker.aws-emulator")
}

val selectedMode = providers.gradleProperty("development").map {
    if (it.toBoolean()) EmulatorMode.DEVELOPMENT else EmulatorMode.MANAGED
}.orElse(EmulatorMode.MANAGED)
val failExample = providers.gradleProperty("failExample").orElse("false")

val pythonProbe = tasks.register<Exec>("pythonProbe") {
    description = "Checks emulator HTTPS using Python's standard library."
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "probe.py", failExample.get())
}
val nodeProbe = tasks.register<Exec>("nodeProbe") {
    description = "Checks emulator HTTPS using Node's standard library."
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "probe.mjs", failExample.get())
}
val awsIdentity = tasks.register<Exec>("awsIdentity") {
    description = "Makes a signed STS call using an installed AWS CLI v2."
    commandLine(providers.gradleProperty("awsExecutable").getOrElse("aws"), "sts", "get-caller-identity", "--no-cli-pager")
}
awsEmulator {
    port.set(0)
    useWith(pythonProbe, selectedMode)
    useWith(nodeProbe, selectedMode)
    useWith(awsIdentity, selectedMode)
}
