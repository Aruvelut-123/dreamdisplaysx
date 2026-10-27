plugins {
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.serialization-conventions")
    id("dreamdisplays.shadow-conventions")
    alias(libs.plugins.platformweaver)
}

platformweaver {
    target = "bungeecord"
    chameleonsDir = null
}

val javaNewerCompileApis: Configuration = configurations.create("javaNewerCompileApis") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}

dependencies {
    javaNewerCompileApis(libs.platformweaverAnnotations)
    compileOnly(files(javaNewerCompileApis))
    compileOnly(libs.velocityApi)
    compileOnly(libs.bungeecordApi)
    compileOnly(libs.slf4jApi)

    implementation(project(":platform:proxy:common"))
    implementation(project(":core"))
    implementation(libs.kotlinStdlib)
    implementation(libs.kotlinxSerializationProtobuf)
}

tasks.processResources {
    val props = mapOf("version" to rootProject.version.toString())
    inputs.properties(props)
    filteringCharset = Charsets.UTF_8.name()
    filesMatching("bungee.yml") {
        expand(props)
    }
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("dreamdisplays-bungeecord")
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "kotlin",
        "kotlinx.serialization",
        "kotlinx.io",
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set(rootProject.version.toString())
}
