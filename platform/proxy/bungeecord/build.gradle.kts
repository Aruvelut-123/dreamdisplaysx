plugins {
    id("dreamdisplayx.kotlin-conventions")
    id("dreamdisplayx.serialization-conventions")
    id("dreamdisplayx.shadow-conventions")
    alias(libs.plugins.platformweaver)
}

platformweaver {
    target = "bungeecord"
    chameleonsDir = null
}

dependencies {
    compileOnly(libs.platformweaverAnnotations)
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
    archiveBaseName.set("dreamdisplayx-bungeecord")
    val prefix = "com.dreamdisplayx.libs"
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
