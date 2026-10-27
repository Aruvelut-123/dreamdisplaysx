@Suppress("UNCHECKED_CAST")
pluginManagement {
    val versionsRoot = groovy.json.JsonSlurper().parse(file("versions.json")) as Map<String, Any?>
    val activeVersion = versionsRoot["active"] as? String
        ?: error("versions.json is missing an 'active' field.")
    val allVersions = versionsRoot["versions"] as? Map<String, Map<String, Any?>>
        ?: error("versions.json is missing a 'versions' object.")
    val activeProps = allVersions[activeVersion]
        ?: error("versions.json has no entry for Minecraft version '$activeVersion'.")

    fun scVersion(name: String): String =
        activeProps[name]?.toString()
            ?: error("Missing Stonecutter version property '$name'.")

    includeBuild("gradle")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Fabric's Maven must be queried before NeoForge's: maven.neoforged.net hangs (no response)
        // instead of returning 404 for artifacts it does not host, which makes Gradle give up on
        // the whole plugin resolution when it is probed first for Loom.
        maven("https://maven.fabricmc.net/")
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://maven.neoforged.net/releases")
        maven("https://prmaven.neoforged.net/NeoForge/pr3403")
        maven("https://maven.parchmentmc.org")
        maven("https://maven.quiltmc.org/repository/release/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    resolutionStrategy {
        eachPlugin {
            val loomVersion = scVersion("loom.version")
            val mcVer = scVersion("minecraft.version")
            // Minecraft 1.20.1 predates the split fabric-loom/fabric-loom-remap plugin ids, so it
            // applies the pre-split `fabric-loom` id. Every Loom line still publishes that id as the
            // `net.fabricmc:fabric-loom` module (no plain plugin marker), hence `useModule` below.
            val legacyLoomModule = mcVer.startsWith("1.20")
            when (requested.id.id) {
                // The build declares the pre-split `fabric-loom` id (`apply false`) in every version so
                // that one jar lands on the build classpath; the runtime `apply(...)` call then picks
                // the remapping variant the target version needs.
                "fabric-loom" -> useModule("net.fabricmc:fabric-loom:$loomVersion")
                "net.fabricmc.fabric-loom" ->
                    if (legacyLoomModule) useModule("net.fabricmc:fabric-loom:$loomVersion")
                    else useVersion(loomVersion)
                "net.fabricmc.fabric-loom-remap" ->
                    // Classic Loom (1.20.1 era and earlier) only publishes the `net.fabricmc:fabric-loom`
                    // module, so the remap plugin id is aliased onto the same module there.
                    if (legacyLoomModule) useModule("net.fabricmc:fabric-loom:$loomVersion")
                    else if (mcVer.startsWith("1.")) useVersion(loomVersion)
                    else useModule("net.fabricmc:fabric-loom:$loomVersion")

                "net.neoforged.moddev" -> useVersion(scVersion("moddev.version"))
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.7"
    id("dreamdisplayx.stonecutter-versions")
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.parchmentmc.org")
        maven("https://maven.quiltmc.org/repository/release/")
        maven("https://maven.quiltmc.org/repository/snapshot/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://jitpack.io")
        maven("https://prmaven.neoforged.net/NeoForge/pr3403")
    }
}

rootProject.name = "dreamdisplayx"
include(":native")
include(":core")
include(":api")
include(":media")
include(":media:runtime")
include(":media:player")
include(":media:source")
include(":media:audio")
include(":util")
include(":platform")
include(":platform:resources")
include(":platform:client")
include(":platform:client:common")
include(":platform:client:fabric")
include(":platform:server")
include(":platform:proxy")
include(":platform:proxy:common")
include(":platform:proxy:velocity")
include(":platform:proxy:bungeecord")

// Not my problem
if (!java.lang.Boolean.getBoolean("idea.sync.active")) {
    include(":platform:client:neoforge")
}

@Suppress("UNCHECKED_CAST")
stonecutter {
    create(rootProject) {
        val versionNames = gradle.extensions.getByName("stonecutterAllVersions") as List<String>
        versions(*versionNames.toTypedArray())
    }
}
