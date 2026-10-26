import support.stonecutter.StonecutterVersions

plugins {
    id("net.neoforged.moddev") apply false
    id("net.neoforged.moddev.legacyforge") apply false
    id("dreamdisplays.kotlin-conventions")
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.fabricmc.net/")
    maven("https://prmaven.neoforged.net/NeoForge/pr3403")
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
}

val scVersions = gradle.extensions.getByType<StonecutterVersions>()
fun scVersion(name: String): String = scVersions.get(name)

val usesLegacyForge = scVersions.getOrNull("forge.version") != null
if (usesLegacyForge) apply(plugin = "net.neoforged.moddev.legacyforge") else apply(plugin = "net.neoforged.moddev")

dependencies {
    api(project(":core"))
    api(project(":api"))
    api(project(":media:runtime"))
    api(project(":media:player"))
    api(project(":media:source"))
    api(project(":media:audio"))
    api(project(":util"))
    api(libs.jspecify)
    api(libs.commonsCompress)
    api(libs.caffeine)
    api(libs.tukaaniXz)
    api(libs.semver4j)
    api(libs.newpipeExtractor)
    api(libs.kotlinxCoroutinesCore)
    scVersions.getOrNull("joml.version")?.let { api("org.joml:joml:$it") }
    compileOnly(libs.kotlinStdlib)
    compileOnly(
        "maven.modrinth:cloth-config:" + (scVersions.getOrNull("cloth.config.forge").takeIf { usesLegacyForge }
            ?: "${scVersion("cloth.config.version")}+${if (usesLegacyForge) "forge" else "neoforge"}")
    )
    if (scVersions.getOrNull("neoform.version") != null) {
        compileOnly("org.spongepowered:mixin:0.8.7")
        compileOnly("org.lwjgl:lwjgl:3.4.3")
        compileOnly("org.lwjgl:lwjgl-glfw:3.4.3")
        compileOnly("org.lwjgl:lwjgl-sdl:3.4.3")
        compileOnly("org.lwjgl:lwjgl-opengl:3.4.3")
    }
}

if (usesLegacyForge) {
    configure<net.neoforged.moddevgradle.legacyforge.dsl.LegacyForgeExtension> {
        enable { mcpVersion = scVersion("minecraft.version") }
    }
    dependencies {
        compileOnly("net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7")
    }
} else {
    configure<net.neoforged.moddevgradle.dsl.NeoForgeExtension> {
        enable {
            val neoForm = scVersions.getOrNull("neoform.version")
            if (neoForm != null) {
                neoFormVersion = neoForm
            } else {
                version = scVersion("neoforge.version")
            }
        }
        accessTransformers.from(
            rootProject.file("platform/client/neoforge/src/main/resources/META-INF/accesstransformer.cfg"),
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-jvm-default=enable")
    }
}
