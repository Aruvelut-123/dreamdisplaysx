package support.shadow

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

/** Shared modules bundled into every fat loader jar (`Fabric`, `NeoForge`, `Paper`). */
val dreamDisplaysSharedModules = listOf(
    ":platform:client:common",
    ":core",
    ":api",
    ":util",
    ":media:runtime",
    ":media:source",
    ":media:player",
    ":media:audio",
)

/** Third-party dependencies bundled into fat client loader jars (superset safe). */
val dreamDisplaysShadedDependencies = listOf(
    "org.jetbrains.kotlinx:kotlinx-serialization-core",
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-serialization-protobuf",
    "org.jetbrains.kotlinx:kotlinx-serialization-protobuf-jvm",
    "org.jetbrains.kotlinx:kotlinx-serialization-json",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-datetime-jvm",
    "org.xerial:sqlite-jdbc",
    "org.apache.commons:commons-compress",
    "org.tukaani:xz",
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains:annotations",
    "org.tomlj:tomlj",
    "org.antlr:antlr4-runtime",
    "org.semver4j:semver4j",
    "com.github.ben-manes.caffeine:caffeine",
    "com.squareup.okhttp3:okhttp",
    "com.squareup.okhttp3:okhttp-jvm",
    "com.squareup.okio:okio",
    "com.squareup.okio:okio-jvm",
    "org.jetbrains.exposed:exposed-core",
    "org.jetbrains.exposed:exposed-jdbc",
    "org.jetbrains.exposed:exposed-migration-core",
    "org.jetbrains.exposed:exposed-migration-jdbc",
    "com.zaxxer:HikariCP",
    "com.google.protobuf:protobuf-javalite",
    "com.google.zxing:core",
    "uk.co.caprica:vlcj",
    "uk.co.caprica:vlcj-natives",
)

/** Packages relocated under `com.dreamdisplayx.libs` in every fat loader jar. */
val dreamDisplaysShadedPackages = listOf(
    "org.apache.commons.compress",
    "org.tukaani.xz",
    "kotlin",
    "kotlinx",
    "org.jetbrains.annotations",
    "org.intellij.lang.annotations",
    "org.tomlj",
    "org.antlr",
    "org.semver4j",
    "com.github.benmanes.caffeine",
    "okhttp3",
    "okio",
    "org.jetbrains.exposed",
    "com.zaxxer.hikari",
    "org.jsoup",
    "com.google.protobuf",
    "org.mozilla.javascript",
    "org.mozilla.classfile",
    "com.google.zxing",
    "org.sqlite",
)

/** Exclude all sqlite-jdbc native binaries (they are downloaded at runtime
 *  by [NativesDownloader]; the stock JNI symbols do not match the relocated
 *  NativeDB class). */
val dreamDisplaysSqliteNativeExcludes = listOf(
    "org/sqlite/native/**",
)

/** Includes the shared `:core`/`:api`/`:util`/`:media:*` modules and third-party dependencies in a fat loader jar. */
fun ShadowJar.includeDreamDisplaysXSharedContents() {
    dependencies {
        dreamDisplaysSharedModules.forEach { include(project(it)) }
        dreamDisplaysShadedDependencies.forEach { include(dependency(it)) }
    }
}

/** Relocates the shared third-party packages under `com.dreamdisplayx.libs` in a fat loader jar. */
fun ShadowJar.relocateDreamDisplaysXSharedPackages(prefix: String = "com.dreamdisplayx.libs") {
    dreamDisplaysShadedPackages.forEach { pack ->
        // The sqlite-jdbc native binaries (org/sqlite/native/**) keep their original path so
        // SQLiteJDBCLoader's hard-coded "org/sqlite/native/..." resource lookup still resolves them.
        // They are rebuilt in CI with relocated JNI symbols to match the relocated NativeDB class.
        if (pack == "org.sqlite") {
            relocate(pack, "$prefix.$pack") {
                exclude("org/sqlite/native/**")
            }
        } else {
            relocate(pack, "$prefix.$pack")
        }
    }
}

/** Excludes sqlite-jdbc's native binaries for platforms this project never runs on. */
fun ShadowJar.excludeDreamDisplaysXSqliteNativeExtras() {
    dreamDisplaysSqliteNativeExcludes.forEach { exclude(it) }
}
