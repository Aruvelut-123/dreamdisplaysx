import support.shadow.excludeDreamDisplaysXSqliteNativeExtras
import support.shadow.includeDreamDisplaysXSharedContents
import support.shadow.relocateDreamDisplaysXSharedPackages
import support.stonecutter.StonecutterVersions
import org.gradle.kotlin.dsl.support.serviceOf

plugins {
    id("net.neoforged.moddev.legacyforge")
    id("dreamdisplayx.kotlin-conventions")
    id("dreamdisplayx.native-resources")
    id("dreamdisplayx.shadow-conventions")
    alias(libs.plugins.platformweaver)
}

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net/")
    maven("https://jitpack.io")
    maven("https://maven.fabricmc.net/")
    maven(rootProject.layout.projectDirectory.dir(".gradle/loom-cache/remapped_mods")) {
        name = "fabricLoomRemappedMods"
    }
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
}

val scVersions = gradle.extensions.getByType<StonecutterVersions>()
val activeStonecutterVersion = scVersions.active
fun scVersion(name: String): String = scVersions.get(name)

fun mainSourceSetOf(path: String): SourceSet =
    project(path).extensions.getByType(SourceSetContainer::class.java).getByName("main")

evaluationDependsOn(":platform:client:fabric")

sourceSets.main {
    kotlin.srcDir(project(":platform:server").layout.buildDirectory.dir("generated/chisel/main/kotlin"))
    resources.srcDir(project(":platform:resources").file("src/main/resources"))
    resources.exclude("assets/dreamdisplayx/lang/client/**")
    resources.exclude("dreamdisplayx.mixins.json", "dreamdisplayx.server.mixins.json")
}

platformweaver {
    target = "forge"
    chameleonsDir = null
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(":platform:server:chiselSource")
    exclude("**/*NeoForge*.kt")
}
tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(":platform:server:chiselSource")
}

val vendoredLibraries: Configuration = configurations.create("vendoredLibraries") {
    isCanBeConsumed = false
    isCanBeResolved = true
    exclude(group = "com.google.code.findbugs", module = "jsr305")
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    exclude(group = "com.google.guava")
    exclude(group = "com.google.j2objc", module = "j2objc-annotations")
    exclude(group = "org.checkerframework", module = "checker-qual")
    exclude(group = "org.antlr", module = "antlr4-runtime")
    exclude(group = "org.slf4j")
    // Forge ships these itself; a second copy is a split package to its module layer
    exclude(group = "commons-io")
    exclude(group = "commons-codec")
    exclude(group = "org.apache.commons", module = "commons-lang3")
    exclude(group = "com.google.code.gson")
    exclude(group = "org.apache.commons", module = "commons-compress")
}

val unpackVendoredLibraries = tasks.register<Sync>("unpackVendoredLibraries") {
    val archives = serviceOf<ArchiveOperations>()
    val elements = vendoredLibraries.elements
    from(elements.map { files -> files.map { archives.zipTree(it) } })
    into(layout.buildDirectory.dir("vendoredLibraries"))
    exclude("module-info.class", "META-INF/versions/**", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

sourceSets.create("vendoredLibraries") {
    output.dir(unpackVendoredLibraries.map { it.destinationDir })
}

configurations.all {
    resolutionStrategy.force("org.slf4j:slf4j-api:2.0.9")
}

// Compile-time-only APIs published for Java 21 (source-retention annotations, WorldGuard, whose runtime copy is
// whatever the server ships). Resolved apart from compileClasspath so its Java 17 request stays strict for the rest.
// Declared once by dreamdisplayx.kotlin-conventions; reused here.
val javaNewerCompileApis: Configuration = configurations.getByName("javaNewerCompileApis")

val isolatedSqliteJdbc = configurations.register("isolatedSqliteJdbc") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    javaNewerCompileApis(libs.platformweaverAnnotations)
    compileOnly(files(javaNewerCompileApis))
    compileOnly(libs.luckpermsApi)
    javaNewerCompileApis(libs.worldguardApi) {
        exclude(group = "com.google.guava", module = "guava")
        exclude(group = "com.google.code.gson", module = "gson")
        exclude(group = "it.unimi.dsi", module = "fastutil")
    }
    compileOnly(libs.bstats)
    compileOnly("io.papermc.paper:paper-api:${scVersion("paper.api.version")}")
    compileOnly("net.fabricmc:fabric-loader:${scVersion("fabric.loader.version")}")
    compileOnly(project(path = ":platform:client:fabric", configuration = "mappedFabricApiElements"))
    compileOnly("maven.modrinth:cloth-config:${scVersions.getOrNull("cloth.config.forge") ?: "${scVersion("cloth.config.version")}+forge"}")
    implementation(project(":platform:client:common"))
    implementation(libs.tomlj)
    implementation(libs.semver4j)
    implementation(libs.exposedCore)
    implementation(libs.exposedJdbc)
    implementation(libs.exposedMigrationJdbc)
    implementation(libs.hikari)
    implementation(libs.kotlinxSerializationProtobuf)
    implementation(libs.kotlinxSerializationJson)
    runtimeOnly(libs.sqliteJdbc)
    isolatedSqliteJdbc(libs.sqliteJdbc)
    vendoredLibraries(libs.kotlinStdlib)
    vendoredLibraries(libs.kotlinxCoroutinesCore)
    vendoredLibraries(libs.kotlinxSerializationProtobuf)
    vendoredLibraries(libs.kotlinxSerializationJson)
    vendoredLibraries(libs.tomlj)
    vendoredLibraries(libs.semver4j)
    vendoredLibraries(libs.caffeine)
    vendoredLibraries(libs.okhttp)
    vendoredLibraries(libs.okio)
    vendoredLibraries(libs.sqliteJdbc)
    vendoredLibraries(libs.exposedCore)
    vendoredLibraries(libs.exposedJdbc)
    vendoredLibraries(libs.exposedMigrationJdbc)
    vendoredLibraries(libs.hikari)
    vendoredLibraries(libs.newpipeExtractor)
    scVersions.getOrNull("joml.version")?.let { vendoredLibraries("org.joml:joml:$it") }
    vendoredLibraries(libs.tukaaniXz)
    shadow(project(":core"))
    shadow(project(":api"))
    shadow(project(":shared"))
    shadow(project(":util"))
    shadow(project(":media:runtime"))
    shadow(project(":media:source"))
    shadow(project(":media:player"))
    shadow(project(":media:audio"))
    shadow(project(":platform:client:common"))
    shadow(libs.kotlinxSerializationProtobuf)
    shadow(libs.kotlinxSerializationJson)
    shadow(libs.kotlinStdlib)
    shadow(libs.tomlj)
    shadow(libs.semver4j)
    shadow(libs.caffeine)
    shadow(libs.okhttp)
    shadow(libs.okio)
    shadow(libs.exposedCore)
    shadow(libs.exposedJdbc)
    shadow(libs.exposedMigrationJdbc)
    shadow(libs.hikari)
    shadow(libs.newpipeExtractor)
    scVersions.getOrNull("joml.version")?.let { shadow("org.joml:joml:$it") }
}

val isAppleSilicon = System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64"
val needsLwjglArm64 = isAppleSilicon && scVersion("minecraft.version").startsWith("1.18")
val lwjglArm64Natives: Configuration = configurations.create("lwjglArm64Natives") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val lwjglArm64Dir = layout.buildDirectory.dir("lwjglArm64Natives")
if (needsLwjglArm64) {
    configurations.configureEach {
        if (name != "lwjglArm64Natives") resolutionStrategy.eachDependency {
            if (requested.group == "org.lwjgl") useVersion("3.3.1")
        }
    }
    dependencies {
        listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openal", "lwjgl-stb", "lwjgl-jemalloc", "lwjgl-tinyfd").forEach {
            lwjglArm64Natives("org.lwjgl:$it:3.3.1:natives-macos-arm64")
        }
    }
}
val unpackLwjglArm64 = tasks.register<Sync>("unpackLwjglArm64Natives") {
    val archives = serviceOf<ArchiveOperations>()
    val jars = lwjglArm64Natives.elements
    from(jars.map { files -> files.map { archives.zipTree(it) } }) { include("**/*.dylib") }
    eachFile { path = name }
    includeEmptyDirs = false
    into(lwjglArm64Dir)
}

legacyForge {
    version = scVersion("forge.version")
    mods {
        register("dreamdisplayx") {
            sourceSet(sourceSets.main.get())
            sourceSet(mainSourceSetOf(":platform:client:common"))
            sourceSet(mainSourceSetOf(":core"))
            sourceSet(mainSourceSetOf(":api"))
            sourceSet(mainSourceSetOf(":shared"))
            sourceSet(mainSourceSetOf(":util"))
            sourceSet(mainSourceSetOf(":media:runtime"))
            sourceSet(mainSourceSetOf(":media:source"))
            sourceSet(mainSourceSetOf(":media:player"))
            sourceSet(mainSourceSetOf(":media:audio"))
            sourceSet(sourceSets["vendoredLibraries"])
        }
    }
    runs {
        register("forgeClient") {
            client()
            if (needsLwjglArm64) jvmArgument("-Dorg.lwjgl.librarypath=${lwjglArm64Dir.get().asFile.absolutePath}")
            providers.gradleProperty("dreamdisplayx.quickPlay").orNull?.let {
                programArguments.addAll("--quickPlayMultiplayer", it)
            }
            environment("DD_NATIVE_LOG", System.getenv("DD_NATIVE_LOG") ?: "debug")
        }
        register("forgeServer") {
            server()
            environment("DD_NATIVE_LOG", System.getenv("DD_NATIVE_LOG") ?: "debug")
        }
    }
}

providers.gradleProperty("dreamdisplayx.runJava").orNull?.toInt()?.let { runJava ->
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(runJava)) }
    tasks.withType<JavaExec>().matching { it.name.startsWith("run") }.configureEach { javaLauncher.set(launcher) }
}

tasks.processResources {
    from(project(":platform:resources").file("src/main/resources/assets/dreamdisplayx/lang/client")) {
        into("assets/dreamdisplayx/lang")
    }
    val projectVersion = project.version.toString()
    val forgeLoaderRange = scVersion("forge.loader.range")
    val minecraftRange = scVersion("forge.minecraft.range")
    inputs.property("version", projectVersion)
    inputs.property("forgeLoaderRange", forgeLoaderRange)
    inputs.property("minecraftRange", minecraftRange)
    filesMatching("META-INF/mods.toml") {
        expand(
            mapOf(
                "version" to projectVersion,
                "forgeLoaderRange" to forgeLoaderRange,
                "minecraftRange" to minecraftRange,
            )
        )
    }
    filesMatching("assets/dreamdisplayx/version.txt") {
        expand(mapOf("version" to projectVersion))
    }
}

java {
    withSourcesJar()
}

val trimmedSqliteJdbc = tasks.register<Zip>("trimmedSqliteJdbc") {
    from(isolatedSqliteJdbc.map { configuration -> configuration.map { zipTree(it) } })
    excludeDreamDisplaysXSqliteNativeExtras()
    archiveFileName.set("sqlite-jdbc.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/isolatedSqliteJdbc"))
}

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    archiveBaseName.set("dreamdisplayx-forge")
    archiveClassifier.set("dev-shadow")
    destinationDirectory.set(layout.buildDirectory.dir("devlibs"))
    includeDreamDisplaysXSharedContents()
    relocateDreamDisplaysXSharedPackages()
    if (scVersions.getOrNull("joml.version") != null) {
        dependencies { include(dependency("org.joml:joml")) }
        relocate("org.joml", "com.dreamdisplayx.libs.org.joml")
    }
    from(trimmedSqliteJdbc) {
        into("META-INF/dreamdisplayx")
    }
}

val reobfShadowJar = obfuscation.reobfuscate(tasks.shadowJar, sourceSets.main.get()) {
    archiveBaseName.set("dreamdisplayx-forge")
    archiveClassifier.set("")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
}

tasks.named("assemble") { dependsOn(reobfShadowJar) }

tasks.register("publishJar") {
    description = "Builds the final, SRG-named Forge jar."
    dependsOn(reobfShadowJar)
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set("$activeStonecutterVersion-${rootProject.version}")
}

if (needsLwjglArm64) {
    tasks.matching { it.name == "prepareForgeClientRun" }.configureEach { dependsOn(unpackLwjglArm64) }
}
