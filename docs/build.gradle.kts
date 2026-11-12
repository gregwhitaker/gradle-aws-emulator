plugins { base }

description = "Static documentation site for the AWS Emulator Gradle plugin"
version = providers.gradleProperty("pluginVersion").get()

repositories { mavenCentral() }

val asciidoctor = configurations.create("asciidoctor") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    asciidoctor("org.asciidoctor:asciidoctorj-cli:3.0.1")
}

val pages = fileTree("src/docs/asciidoc") { include("*.adoc") }
val pageNames = pages.files.map { it.name }.sorted()
val preparedSources = layout.buildDirectory.dir("sources")
val renderedPages = layout.buildDirectory.dir("rendered")
val siteDirectory = layout.buildDirectory.dir("site")

val prepareDocs = tasks.register<Sync>("prepareDocs") {
    description = "Stages documentation and the runnable example used by the guide."
    from("src/docs/asciidoc")
    from(rootProject.layout.projectDirectory.dir("examples/basic")) {
        include("settings.gradle.kts", "build.gradle.kts", "src/integration/java/**/*.java", "src/integration/messages/**")
        into("examples/basic")
    }
    from(rootProject.layout.projectDirectory.dir("examples/dynamodb")) {
        include("settings.gradle.kts", "build.gradle.kts", "buildSrc/settings.gradle.kts", "buildSrc/build.gradle.kts",
            "buildSrc/src/main/java/**/*.java")
        into("examples/dynamodb")
    }
    into(preparedSources)
}

// Use the JVM CLI with standard Gradle tasks to support both Gradle 8/9 and
// configuration-cache reuse without requiring Node, Python, or a Ruby install.
val renderDocs = tasks.register<JavaExec>("renderDocs") {
    description = "Converts the AsciiDoc pages to HTML, failing on documentation warnings."
    group = "documentation"
    dependsOn(prepareDocs)
    classpath = asciidoctor
    mainClass.set("org.asciidoctor.cli.jruby.AsciidoctorInvoker")
    inputs.dir(preparedSources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("pluginVersion", project.version.toString())
    outputs.dir(renderedPages)
    args(
        // Trusted repository sources write to a sibling build output directory.
        "--safe-mode", "unsafe",
        "--failure-level", "WARN",
        "--base-dir", preparedSources.get().asFile.absolutePath,
        "--destination-dir", renderedPages.get().asFile.absolutePath,
        "--attribute", "revnumber=${project.version}",
        "--attribute", "attribute-missing=warn"
    )
    args(pageNames.map { preparedSources.get().file(it).asFile.absolutePath })
}

val site = tasks.register<Sync>("site") {
    description = "Generates the complete static site in docs/build/site."
    group = "documentation"
    dependsOn(renderDocs, ":plugin:javadoc", ":spring-boot-plugin:javadoc")
    val apiHeadFile = layout.projectDirectory.file("src/templates/api-head.html")
    val apiHeaderFile = layout.projectDirectory.file("src/templates/api-header.html")
    val apiHead = providers.fileContents(apiHeadFile).asText.get()
    val apiHeader = providers.fileContents(apiHeaderFile).asText.get()
    inputs.files(apiHeadFile, apiHeaderFile).withPathSensitivity(PathSensitivity.RELATIVE)
    from(renderedPages) {
        // Exclude obsolete pages left by earlier renders; Sync removes them from the site.
        include(pageNames.map { it.removeSuffix(".adoc") + ".html" })
    }
    from("src/site")
    for ((module, destination) in listOf("plugin" to "api", "spring-boot-plugin" to "spring-boot-api")) {
        from(project(":$module").layout.buildDirectory.dir("docs/javadoc")) {
            into(destination)
            filteringCharset = "UTF-8"
            filesMatching("**/*.html") {
                // The destination path includes the API directory, so nested package pages resolve
                // shared assets and the guide correctly even when hosted under a prefix.
                val siteRoot = "../".repeat(relativePath.segments.size - 1)
                val head = apiHead.replace("@site-root@", siteRoot)
                val header = apiHeader.replace("@site-root@", siteRoot)
                filter { line: String ->
                    line.replace("</head>", "$head\n</head>")
                        .replace("<header role=\"banner\" class=\"flex-header\">",
                            "<header role=\"banner\" class=\"flex-header\">\n$header")
                }
            }
            filesMatching("stylesheet.css") {
                // The shared theme uses system fonts; skip the doclet's unused font import.
                filter { line: String ->
                    if (line.trim() == "@import url('resources/fonts/dejavu.css');") "" else line
                }
            }
        }
    }
    from(rootProject.layout.projectDirectory.file("LICENSE"))
    into(siteDirectory)
}

val checkSite = tasks.register<JavaExec>("checkSite") {
    description = "Checks generated HTML for broken local links, anchors, and missing assets."
    group = "verification"
    dependsOn(site)
    val checker = layout.projectDirectory.file("src/tools/CheckSite.java")
    // Java 17 source-file launch keeps the verifier dependency-free.
    mainClass.set(checker.asFile.absolutePath)
    args(siteDirectory.get().asFile.absolutePath)
    inputs.file(checker).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(siteDirectory).withPathSensitivity(PathSensitivity.RELATIVE)
}

val siteZip = tasks.register<Zip>("siteZip") {
    description = "Packages the verified documentation site for any static host."
    group = "distribution"
    dependsOn(checkSite)
    from(siteDirectory)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveBaseName.set("aws-emulator-docs")
    archiveVersion.set(project.version.toString())
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named("assemble") { dependsOn(siteZip) }
tasks.named("check") { dependsOn(checkSite) }
