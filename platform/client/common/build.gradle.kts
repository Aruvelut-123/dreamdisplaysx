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
 * that target - and applying Loom here instead is not an option either (see below). 1.20.1 therefore
 * gets no Minecraft provider at all while every other version keeps using `moddev`. The plugin is
 * applied here, before any accessor is touched, because `apply false` only puts the jar on the
 * classpath and the extension does not exist until the plugin is actually applied.
 */
val compilesCommonElsewhere = scVersion("minecraft.version").startsWith("1.20")
// 1.20.1 applies *no* Minecraft provider here. Applying a Loom in this module as well as in
// `:platform:client:fabric` puts a second `LoomGradleExtension` on a second plugin classloader, and
// `fabric`'s `remapJar` then dies evaluating `tinyRemapperServiceOptions` with
// `LoomGradleExtensionImpl_Decorated cannot be cast to LoomGradleExtension`. That holds for every
// Loom line (verified with 1.10.5 and with 1.17.13), so it cannot be dodged by moving Loom.
//
// The fix is the same shape `:platform:server` already uses: this module keeps the sources,
// `:platform:client:fabric` compiles them against the target's classpath and its `remapJar` maps the
// result to `intermediary`. See the `sourceSets.main` note in that module for the wiring.
if (!compilesCommonElsewhere) {
    apply(plugin = "net.neoforged.moddev")
}

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
    // 1.20.1 declares no Minecraft/Fabric coordinates: `:platform:client:fabric` compiles this
    // module's sources against its own classpath. See the plugin note above.
}

if (!compilesCommonElsewhere) {
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

/**
 * 1.20.1 compiles this module's sources inside `:platform:client:fabric`, which owns the only Loom
 * instance for that target. Nothing is compiled here, so the compile tasks are disabled outright
 * rather than left to fail on the missing Minecraft classpath. The chisel output that `fabric`
 * consumes is still produced by this module's `chiselSource` task.
 */
if (compilesCommonElsewhere) {
    tasks.matching {
        it.name == "compileKotlin" || it.name == "compileJava" ||
            it.name == "compileTestKotlin" || it.name == "compileTestJava"
    }.configureEach {
        enabled = false
    }
}
