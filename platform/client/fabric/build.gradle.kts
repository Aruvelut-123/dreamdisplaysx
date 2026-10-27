import org.gradle.api.attributes.java.TargetJvmVersion
import support.shadow.excludeDreamDisplaysXSqliteNativeExtras
import support.shadow.includeDreamDisplaysXSharedContents
import support.shadow.relocateDreamDisplaysXSharedPackages
import support.stonecutter.StonecutterVersions

plugins {
    // Single-segment `fabric-loom` id is the one present in every Loom line we build against: 1.17.x
    // exports it next to `net.fabricmc.fabric-loom` / `net.fabricmc.fabric-loom-remap` from the same
    // jar. Declaring this one `apply false` only puts the jar on the build classpath; the actual
    // plugin is selected at runtime below.
    id("fabric-loom") apply false
    id("maven-publish")
    id("dreamdisplayx.kotlin-conventions")
    id("dreamdisplayx.native-resources")
    id("dreamdisplayx.shadow-conventions")
    alias(libs.plugins.platformweaver)
}

// Loom plugin id depends on whether the target Minecraft is obfuscated.
// Legacy releases (1.20.1 / 1.21.1 / 1.21.11) ship obfuscated -> a remapping Loom.
// Year-versioned releases (26.x) ship deobfuscated -> net.fabricmc.fabric-loom.
// 1.20.1 keeps the pre-split single-segment `fabric-loom` id: it is the one id present in every
// Loom line this build has been used with, and 1.17.13 still exports it next to the split variants.
// The plugin version is supplied per Stonecutter version via settings.gradle.kts resolutionStrategy.
run {
    val mcVersion = gradle.extensions.getByType<StonecutterVersions>().get("minecraft.version")
    val isLegacyObfuscated = mcVersion.startsWith("1.")
    val usesLegacyLoomId = mcVersion.startsWith("1.20")
    when {
        usesLegacyLoomId -> apply(plugin = "fabric-loom")
        isLegacyObfuscated -> apply(plugin = "net.fabricmc.fabric-loom-remap")
        else -> apply(plugin = "net.fabricmc.fabric-loom")
    }
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/releases/")
    maven("https://maven.shedaniel.me/")
    maven("https://maven.parchmentmc.org")
    maven("https://maven.quiltmc.org/repository/release/")
    maven("https://maven.quiltmc.org/repository/snapshot/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io")
    maven("https://maven.neoforged.net/releases")
    maven("https://prmaven.neoforged.net/NeoForge/pr3403")
    maven("https://maven.enginehub.org/repo/")
}

val scVersions = gradle.extensions.getByType<StonecutterVersions>()
val activeStonecutterVersion = scVersions.active
fun scVersion(name: String): String = scVersions.get(name)

// Legacy (obfuscated) Minecraft targets need layered Mojang+Parchment mappings and modImplementation;
// year-versioned (deobfuscated) targets resolve the source set directly with plain implementation.
val isLegacyObfuscated = scVersion("minecraft.version").startsWith("1.")

fun fancyModLoaderVersion(neoForgeVersion: String): String = when (neoForgeVersion) {
    "21.1.233" -> "4.0.42"
    "21.11.42" -> "10.0.36"
    else -> "11.0.13"
}

sourceSets.main {
    // Consume :platform:server's chiseled output (version directives already resolved) rather than its raw
    // source, so the active Minecraft version's branch is compiled here too.
    kotlin.srcDir(project(":platform:server").layout.buildDirectory.dir("generated/chisel/main/kotlin"))
    // 1.20.1 compiles `:platform:client:common`'s sources here as well. Loom may be applied to exactly
    // one module per target: two copies put two `LoomGradleExtension` classes on two plugin
    // classloaders and `remapJar` then dies evaluating `tinyRemapperServiceOptions` with
    // `LoomGradleExtensionImpl_Decorated cannot be cast to LoomGradleExtension`. That holds for every
    // Loom line (1.10.5 and 1.17.13 alike), so `common` applies no provider for this target and its
    // compile tasks are disabled - its (already version-resolved) sources are compiled here, against
    // this module's classpath, and `remapJar` below maps the result to `intermediary` as usual.
    if (scVersion("minecraft.version").startsWith("1.20")) {
        val commonProject = project(":platform:client:common")
        val commonChisel = commonProject.layout.buildDirectory.dir("generated/chisel/main/kotlin")
        // The convention plugin rewrites `compileKotlin`'s source inside its own `afterEvaluate` to
        // `[own chisel] + otherDirs`, so anything added to the source set before that point can be
        // dropped. Registering here — in a later `afterEvaluate` on this project — appends the
        // directory to the already-rewritten source list instead of racing with it.
        afterEvaluate {
            tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
                dependsOn(commonProject.tasks.named("chiselSource"))
                source(commonChisel)
            }
        }
    }
    // Translations live once in :platform:resources and are pulled in here instead of being duplicated per platform.
    // The lang/client/ split is source-tree organization only; vanilla's language system requires the actual
    // jar to have client lang files directly under assets/dreamdisplayx/lang/, so processResources flattens it back.
    resources.srcDir(project(":platform:resources").file("src/main/resources"))
    resources.exclude("assets/dreamdisplayx/lang/client/**")
}

platformweaver {
    target = "fabric"
    chameleonsDir = null
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(":platform:server:chiselSource")
}
tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(":platform:server:chiselSource")
}

val sourceClassTweaker = project(":platform:client:common").file("src/main/resources/dreamdisplayx.classtweaker")
// `common` applies no Minecraft provider for 1.20.1, so this module owns its compiled sources too.
val compilesCommonSources = scVersion("minecraft.version").startsWith("1.20")
val classTweakerNamespace = if (isLegacyObfuscated) "named" else "official"
val generatedClassTweaker = layout.buildDirectory.file("generated/classtweaker/dreamdisplayx.classtweaker").get().asFile
run {
    val rewritten = if (compilesCommonSources) {
        // None of the widened members the newer targets need exist in 1.20.1 - the render pipeline
        // API this tweaker opens up is not there at all - so the 1.20.1 variant is header-only. The
        // header stays in the dev namespace Loom validates against; `remapJar` rewrites it to
        // `intermediary` for the shipped jar exactly like the other obfuscated targets.
        "accessWidener v1 $classTweakerNamespace\n"
    } else {
        sourceClassTweaker.readText().lineSequence().joinToString("\n") { line ->
            if (line.startsWith("classTweaker v1 ")) "classTweaker v1 $classTweakerNamespace" else line
        }
    }
    if (!generatedClassTweaker.exists() || generatedClassTweaker.readText() != rewritten) {
        generatedClassTweaker.parentFile.mkdirs()
        generatedClassTweaker.writeText(rewritten)
    }
}

val loomExt = the<net.fabricmc.loom.api.LoomGradleExtensionAPI>()
loomExt.accessWidenerPath.set(generatedClassTweaker)

// Dev runs get native-library debug logging by default
loomExt.runs.configureEach {
    environmentVariable("DD_NATIVE_LOG", System.getenv("DD_NATIVE_LOG") ?: "debug")
}

loomExt.runs.named("client") {
    programArgs("--username", "developer")
}

configurations.register("mappedFabricApiElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.getByName("modCompileClasspathMapped"))
}

// Several compile-time-only dependencies (platformweaver-annotations, Paper, NeoForge, LuckPerms,
// WorldGuard) are published as Java 21+ bytecode, while this module's toolchain follows the Minecraft
// target's `java.version` (17 for 1.20.1). Gradle's variant matching then rejects those modules
// outright instead of merely warning, so a Java 17 target cannot resolve them at all. They must stay
// resolvable: PlatformWeaver's annotations drive the Kotlin IR plugin, and the rest are the server
// platforms' own API stubs whose sources this module compiles. Declaring a Java 25 consumer on the
// compile classpath makes every module resolvable while `options.release` / `jvmTarget` still emit
// Java 17 bytecode. This is also why 1.20.1 builds against Loom 1.17.13 rather than the 1.10.5 line
// that line-up used: 1.17.13's TinyRemapper reads Java 25 class files, while 1.10.5 aborts remapping
// with `Unsupported class file major version 69`.
if (compilesCommonSources) {
    listOf("compileClasspath", "testCompileClasspath").forEach { name ->
        configurations.findByName(name)?.attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
}
dependencies {
    compileOnly(libs.platformweaverAnnotations)
    compileOnly(libs.luckpermsApi)
    // ModMenu is a soft dependency: only needed at compile time so we can provide a config screen button.
    // Legacy (obfuscated) targets use modCompileOnly so Loom remaps ModMenu's Screen signature; 26.x
    // ships deobfuscated so plain compileOnly is correct there.
    if (isLegacyObfuscated) {
        "modCompileOnly"("com.terraformersmc:modmenu:${scVersion("modmenu.version")}")
        "modCompileOnly"("me.shedaniel.cloth:cloth-config-fabric:${scVersion("cloth.config.version")}")
    } else {
        compileOnly("com.terraformersmc:modmenu:${scVersion("modmenu.version")}")
        compileOnly("me.shedaniel.cloth:cloth-config-fabric:${scVersion("cloth.config.version")}")
    }
    compileOnly("io.papermc.paper:paper-api:${scVersion("paper.api.version")}")
    compileOnly("net.neoforged:bus:8.0.5")
    compileOnly("net.neoforged.fancymodloader:loader:${fancyModLoaderVersion(scVersion("neoforge.version"))}")
    if (compilesCommonSources) {
        // 1.20.1 sees the server sources through the chiseled output below, whose command tree needs
        // brigadier; every other target receives it transitively through NeoForge/Paper.
        compileOnly("com.mojang:brigadier:${scVersion("brigadier.version")}")
    } else {
        compileOnly("net.neoforged:neoforge:${scVersion("neoforge.version")}:universal")
        compileOnly(libs.worldguardApi) {
            exclude(group = "com.google.guava", module = "guava")
            exclude(group = "com.google.code.gson", module = "gson")
            exclude(group = "com.google.code.findbugs", module = "jsr305")
            exclude(module = "fastutil")
        }
    }
    implementation(libs.bstats)
    implementation(libs.tomlj)
    implementation(libs.semver4j)
    implementation(libs.exposedCore)
    implementation(libs.exposedJdbc)
    implementation(libs.exposedMigrationJdbc)
    implementation(libs.hikari)
    runtimeOnly(libs.sqliteJdbc)

    "minecraft"("com.mojang:minecraft:${scVersion("fabric.minecraft.version")}")
    if (isLegacyObfuscated) {
        "mappings"(loomExt.layered {
            officialMojangMappings()
            // Older legacy targets (1.21.1) predate the io.papermc.parchment.data coordinates and
            // ship under org.parchmentmc.data; allow the version to override the default artifact.
            parchment(
                scVersions.getOrNull("parchment.dependency")
                    ?: "io.papermc.parchment.data:parchment:${scVersion("minecraft.version")}+build.3"
            )
        })
        "modImplementation"("net.fabricmc:fabric-loader:${scVersion("fabric.loader.version")}")
        "modImplementation"("net.fabricmc.fabric-api:fabric-api:${scVersion("fabric.api.version")}")
    } else {
        implementation("net.fabricmc:fabric-loader:${scVersion("fabric.loader.version")}")
        implementation("net.fabricmc.fabric-api:fabric-api:${scVersion("fabric.api.version")}")
    }
    // 1.20.1 compiles `common`'s sources directly (see the `sourceSets.main` block above), so the
    // project dependency must be dropped: `common` produces no classes for this target and depending
    // on it would make `shadowJar` look for an artifact that never appears. Its own third-party
    // dependencies still have to be on this module's compile classpath, though, because those
    // sources import them.
    if (!compilesCommonSources) {
        implementation(project(":platform:client:common"))
    } else {
        compileOnly(libs.jspecify)
        compileOnly(libs.commonsCompress)
        compileOnly(libs.caffeine)
        compileOnly(libs.tukaaniXz)
        compileOnly(libs.zxingCore)
        compileOnly(libs.okhttp)
        compileOnly(libs.okio)
        compileOnly(libs.kotlinxCoroutinesCore)
    }
    shadow(project(":core"))
    shadow(project(":api"))
    shadow(project(":util"))
    shadow(project(":media:runtime"))
    shadow(project(":media:source"))
    shadow(project(":media:player"))
    shadow(project(":media:audio"))
    // `common`'s classes are compiled into this module for 1.20.1 (see `sourceSets.main`), so they are
    // already part of the project output and must not be shadowed a second time.
    if (!compilesCommonSources) {
        shadow(project(":platform:client:common"))
    }
    shadow(libs.kotlinxSerializationProtobuf)
    shadow(libs.kotlinxSerializationJson)
    shadow(libs.kotlinStdlib)
    shadow(libs.tomlj)
    shadow(libs.semver4j)
    shadow(libs.caffeine)
    shadow(libs.okhttp)
    shadow(libs.okio)
    shadow(libs.sqliteJdbc)
    shadow(libs.exposedCore)
    shadow(libs.exposedJdbc)
    shadow(libs.exposedMigrationJdbc)
    shadow(libs.hikari)
    shadow(libs.zxingCore)
}

tasks.processResources {
    from(generatedClassTweaker)
    from(project(":platform:resources").file("src/main/resources/assets/dreamdisplayx/lang/client")) {
        into("assets/dreamdisplayx/lang")
    }
    val projectVersion = project.version.toString()
    val fabricMcVer = scVersion("fabric.minecraft.dependency")
    val javaVersion = scVersion("java.version")
    // 1.20.1 builds against Fabric Loader 0.16.x, older versions need newer loaders; keep the
    // declared floor in step with the loader each target actually compiles against.
    val fabricLoaderVersion = scVersion("fabric.loader.version")
    val minFabricLoader = if (fabricLoaderVersion.startsWith("0.16.")) ">=0.16" else ">=0.19"
    inputs.property("version", projectVersion)
    inputs.property("minecraftVersion", fabricMcVer)
    inputs.property("javaVersion", javaVersion)
    inputs.property("fabricLoaderVersion", minFabricLoader)
    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to projectVersion,
                "minecraftVersion" to fabricMcVer,
                "javaVersion" to javaVersion,
                "fabricLoaderVersion" to minFabricLoader,
            )
        )
    }
    filesMatching("quilt.mod.json") {
        expand(mapOf("version" to projectVersion, "minecraftVersion" to fabricMcVer, "javaVersion" to javaVersion))
    }
    filesMatching(listOf("dreamdisplayx.mixins.json", "dreamdisplayx.server.mixins.json")) {
        expand(mapOf("javaVersion" to javaVersion))
    }
    filesMatching("assets/dreamdisplayx/version.txt") {
        expand(mapOf("version" to projectVersion))
    }
}

java {
    withSourcesJar()
}

// Hack: it's a bug in Loom alpha where the validation task expects a named namespace but the classtweaker correctly uses
// official namespaces, so we have to disable the validation until it's fixed.
// TODO: when a stable Loom for 26.1.2/26.2 is released, this should be removed
tasks.findByName("validateAccessWidener")?.enabled = false

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    archiveBaseName.set("dreamdisplayx-fabric")
    archiveVersion.set("$activeStonecutterVersion-${rootProject.version}")
    if (isLegacyObfuscated) {
        // The shadow jar is always `remapJar`'s input, never the shipped artifact: keep it in
        // devlibs under the dev classifier so `remapJar` writes the only file in build/libs.
        archiveClassifier.set("dev-shadow")
        destinationDirectory.set(layout.buildDirectory.dir("devlibs"))
    }
    includeDreamDisplaysXSharedContents()
    relocateDreamDisplaysXSharedPackages()
    excludeDreamDisplaysXSqliteNativeExtras()
}

// Every obfuscated target ships a remapped jar: the shadow jar holds `named` bytecode while the game
// resolves `intermediary` - 1.20.1's `net/minecraft/commands/synchronization/SingletonArgumentInfo`
// is really `class_2319` at runtime - so skipping this step yields a mod that dies on
// `NoClassDefFoundError` before its entrypoint runs. 1.20.1's `common` sources are compiled into this
// module, which puts them under this same remap pass.
if (isLegacyObfuscated) {
    tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
        dependsOn(tasks.shadowJar)
        inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
        addNestedDependencies.set(false)
        archiveBaseName.set("dreamdisplayx-fabric")
        archiveVersion.set("$activeStonecutterVersion-${rootProject.version}")
        archiveClassifier.set("")
        destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
    }
}
tasks.register("publishJar") {
    description = "Publish the final jar to the local Maven repository."
    dependsOn(
        when {
            isLegacyObfuscated -> "remapJar"
            else -> "shadowJar"
        }
    )
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set("$activeStonecutterVersion-${rootProject.version}")
}
