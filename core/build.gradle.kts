plugins {
    id("dreamdisplayx.kotlin-conventions")
    id("dreamdisplayx.serialization-conventions")
}

dependencies {
    api(project(":api"))
    api(libs.kotlinxSerializationProtobuf)
    compileOnly(libs.slf4jApi)
    testImplementation(libs.slf4jApi)
}

// Regenerates the committed .proto schema artifact from the @Serializable wire classes.
tasks.register<JavaExec>("generateProto") {
    group = "build"
    description = "Regenerates src/main/proto/dreamdisplayx.proto from the packet classes."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.dreamdisplayx.core.protocol.common.SchemaExporterKt")
    args(layout.projectDirectory.file("src/main/proto/dreamdisplayx.proto").asFile.absolutePath)
}
