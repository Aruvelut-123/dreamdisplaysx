plugins {
    id("dreamdisplayx.kotlin-conventions")
    id("dreamdisplayx.serialization-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":media:runtime"))
    api(project(":util"))
    api(libs.caffeine)
    api(project(":media:player"))
    api(libs.commonsCompress)
    api(libs.tukaaniXz)
    api(libs.kotlinxCoroutinesCore)
    api(libs.kotlinxSerializationJson)
    api(libs.kotlinxSerializationProtobuf)
    compileOnly(libs.slf4jApi)
    testImplementation(libs.slf4jApi)
}
