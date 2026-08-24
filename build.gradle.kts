import support.stonecutter.StonecutterVersions

subprojects {
    val activeStonecutterVersion = gradle.extensions.getByType<StonecutterVersions>().active

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        archiveVersion.set("$activeStonecutterVersion-${project.version}")
    }

    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
        // Inject the short git commit hash into every resource processing run so that
        // commit.txt gets the real value. The lazy provider avoids running git when the
        // task is up-to-date or the build does not touch resources.
        val commitId = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.map { it.trim() }
        inputs.property("commit", commitId)
        filesMatching("assets/dreamdisplayx/commit.txt") {
            expand(mapOf("commit" to commitId.get()))
        }
    }
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.quiltmc.org/repository/release/")
        maven("https://maven.quiltmc.org/repository/snapshot/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://jitpack.io")
    }
}
