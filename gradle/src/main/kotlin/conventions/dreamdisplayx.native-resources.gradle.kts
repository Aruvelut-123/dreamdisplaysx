/**
 * Native resources convention — kept as a no-op after the JavaCPP migration.
 *
 * The Rust native libraries (dreamdisplayx_native, dreamdisplayx_lav) have been replaced by
 * JavaCPP (org.bytedeco:javacv + ffmpeg-platform), which bundles its own native binaries
 * through Maven coordinates. The sqlite-jdbc native rebuild with relocated JNI symbols is
 * handled separately by [support.shadow.includeRebuiltSqliteNatives] in the shadow jar.
 *
 * This convention plugin is retained as an empty shell so that the
 * `id("dreamdisplayx.native-resources")` plugin reference in Fabric/NeoForge build.gradle.kts
 * does not need to be removed (it resolves to a no-op).
 */