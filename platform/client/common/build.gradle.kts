import support.stonecutter.StonecutterVersions

plugins {
    id("net.neoforged.moddev") apply false
    id("net.neoforged.moddev.legacyforge") apply false
    id("dreamdisplayx.kotlin-conventions")
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://prmaven.neoforged.net/NeoForge/pr3403")
}

val scVersions = gradle.extensions.getByType<StonecutterVersions>()
fun scVersion(name: String): String = scVersions.get(name)

// 1.18.2 / 1.19.4 / 1.20.1 are MCP-named Forge targets and need the legacy-forge flavour
// of moddev; every modern target keeps the plain NeoForge plugin.
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
    api(libs.kotlinxCoroutinesCore)
    api(libs.zxingCore)
    testImplementation(libs.slf4jApi)
    api(libs.okhttp)
    api(libs.okio)
    api(libs.tomlj)
    compileOnly(libs.checkerQual)
    compileOnly(libs.kotlinStdlib)
    scVersions.getOrNull("joml.version")?.let { api("org.joml:joml:$it") }
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
