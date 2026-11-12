// SPDX-FileCopyrightText: Bruno Pacheco
//
// SPDX-License-Identifier: Apache-2.0

// Publishes the Gradle plugin to the Gradle Plugin Portal. Nothing is built here: the publication carries the
// jars Maven built and Maven's POM, so the Portal serves the same files as Maven Central, which Reproducible
// Central rebuilds (the Portal writes the plugin marker itself). Expected in place:
// ../target/praecepta-gradle-plugin-X.Y.Z{,-sources,-javadoc}.jar, and ../pom.xml at version X.Y.Z.
// The release workflow fetches them from Maven Central and runs, with GRADLE_PUBLISH_KEY and
// GRADLE_PUBLISH_SECRET set (add --validate-only to check without publishing),
//   gradle -p praecepta-gradle-plugin/portal publishPlugins -Ppraecepta.version=X.Y.Z
// The Gradle versions workflow checks, on every change to the plugin, that what this build would publish is
// byte for byte what Maven built: gradle publishAllPublicationsToLocalRepository writes it to build/repo.
// Gradle's version and checksum: .github/scripts/install_gradle.sh. The plugin-publish jars are pinned by
// SHA-256 in gradle/verification-metadata.xml (gradle --write-verification-metadata sha256 help).
plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.2.1"
}

val releaseVersion = providers.gradleProperty("praecepta.version").get()
val built = layout.projectDirectory.dir("../target")

group = "io.github.brunopacheco1"
version = releaseVersion

gradlePlugin {
    website = "https://bruno.pacheco.lu/praecepta/"
    vcsUrl = "https://github.com/brunopacheco1/praecepta"
    plugins {
        create("praecepta") {
            id = "io.github.brunopacheco1.praecepta"
            implementationClass = "io.github.brunopacheco1.praecepta.gradle.PraeceptaPlugin"
            displayName = "Praecepta"
            description = "Compiles Excel, CSV and DMN decision tables at build time: validates them, converts " +
                "them to DMN resources and generates typed input and output records and a decision class."
            tags = listOf("dmn", "decision-tables", "rules", "code-generation", "validation")
        }
    }
}

// the jars Maven built, in place of what Gradle would build
fun builtJar(classifier: String) =
    built.file("praecepta-gradle-plugin-$releaseVersion${if (classifier.isEmpty()) "" else "-$classifier"}.jar")

publishing.publications.withType<MavenPublication>().matching { it.name == "pluginMaven" }.configureEach {
    setArtifacts(emptyList<Any>())
    artifact(builtJar(""))
    artifact(builtJar("sources")) { classifier = "sources" }
    artifact(builtJar("javadoc")) { classifier = "javadoc" }
}

// Maven's POMs, byte for byte as published to Maven Central, in place of the ones Gradle writes. They are
// copied over Gradle's files once written, as Gradle edits a POM given through pom.withXml (it adds an XML
// declaration, and the marker's dependency). Each must already be at the version being published.
val mavenPoms = mapOf(
    "generatePomFileForPluginMavenPublication" to "../pom.xml",
    "generatePomFileForPraeceptaPluginMarkerMavenPublication" to "../../praecepta-gradle-plugin-marker/pom.xml",
)
tasks.withType<GenerateMavenPom>().configureEach {
    val source = layout.projectDirectory.file(mavenPoms.getValue(name)).asFile
    doLast {
        val pom = source.readBytes()
        check("<version>$releaseVersion</version>" in String(pom)) { "$source is not at version $releaseVersion" }
        destination.writeBytes(pom)
    }
}

// Gradle module metadata would describe Gradle's own jar, which is not published
tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }
// nothing to compile, check or package: the classes and jars come from the Maven build
listOf("compileJava", "processResources", "classes", "jar", "sourcesJar", "javadocJar", "javadoc",
    "pluginDescriptors", "pluginUnderTestMetadata", "validatePlugins").forEach { name ->
    tasks.matching { it.name == name }.configureEach { enabled = false }
}

// a local repository, to see what would be published
publishing.repositories.maven {
    name = "local"
    url = uri(layout.buildDirectory.dir("repo"))
}
