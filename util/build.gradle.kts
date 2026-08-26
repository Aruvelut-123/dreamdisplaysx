plugins {
    id("dreamdisplayx.kotlin-conventions")
}

dependencies {
    api(project(":api"))
    api(libs.caffeine)
    api(libs.kotlinxCoroutinesCore)
    api(libs.kotlinxSerializationJson)
    api(libs.kotlinxIoCore)
    implementation(libs.okhttp)
    implementation(libs.commonsCompress)
    compileOnly(libs.slf4jApi)
    compileOnly(libs.semver4j)
    testImplementation(libs.slf4jApi)
    testImplementation(libs.semver4j)
}
