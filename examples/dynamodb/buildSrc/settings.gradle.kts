rootProject.name = "buildSrc"

includeBuild("../../..") {
    dependencySubstitution {
        substitute(module("io.github.gregwhitaker:gradle-aws-emulator")).using(project(":plugin"))
    }
}
