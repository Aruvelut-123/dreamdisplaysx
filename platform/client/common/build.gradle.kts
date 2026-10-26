import net.fabricmc.loom.api.LoomGradleExtensionAPI
import support.stonecutter.StonecutterVersions

plugins {
    // Both Minecraft provider plugins are declared `apply false` so their jars land on the build
    // classpath; exactly one of them is applied below depending on the target Minecraft version.
    id("fabric-loom") apply false
    id("net.neoforged.moddev") apply false
    id("dreamdisplayx.kotlin-conventions")
}

val scVersions = gradle.extensions.getByType<StonecutterVersions>()
fun scVersion(name: String): String = scVersions.get(name)

/**
 * Minecraft 1.20.1 predates NeoForge (its first release targets 1.20.2 and its loader was still
 * published as `net.neoforged:forge`), so the `moddev` plugin cannot supply Minecraft classes for
 * that target. Classic Fabric Loom can, so 1.20.1 swaps the Minecraft provider while every other
 * version keeps using `moddev`. The plugin is applied here, before any accessor is touched, because
 * `apply false` only puts the jar on the classpath and the extension does not exist until the plugin
 * is actually applied.
 */
val isClassicLoomTarget = scVersion("minecraft.version").startsWith("1.20")
apply(plugin = if (isClassicLoomTarget) "fabric-loom" else "net.neoforged.moddev")

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.fabricmc.net/")
    maven("https://prmaven.neoforged.net/NeoForge/pr3403")
}

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
    if (scVersions.getOrNull("neoform.version") != null) {
        compileOnly("org.spongepowered:mixin:0.8.7")
        compileOnly("org.lwjgl:lwjgl:3.4.3")
        compileOnly("org.lwjgl:lwjgl-glfw:3.4.3")
        compileOnly("org.lwjgl:lwjgl-sdl:3.4.3")
        compileOnly("org.lwjgl:lwjgl-opengl:3.4.3")
    }
    if (isClassicLoomTarget) {
        "minecraft"("com.mojang:minecraft:${scVersion("fabric.minecraft.version")}")
        "mappings"(
            project.extensions.getByType(LoomGradleExtensionAPI::class.java).layered {
                officialMojangMappings()
                parchment(
                    scVersions.getOrNull("parchment.dependency")
                        ?: "org.parchmentmc.data:parchment:${scVersion("minecraft.version")}+build.3"
                )
            }
        )
        "modImplementation"("net.fabricmc:fabric-loader:${scVersion("fabric.loader.version")}")
        "modImplementation"("net.fabricmc.fabric-api:fabric-api:${scVersion("fabric.api.version")}")
    }
}

if (!isClassicLoomTarget) {
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
            project(":platform:client:neoforge").file("src/main/resources/META-INF/accesstransformer.cfg"),
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-jvm-default=enable")
    }
}