plugins {
    id("dreamdisplayx.kotlin-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":media:runtime"))
    api(project(":util"))
    api(libs.commonsCompress)
    api(libs.tukaaniXz)
    api(libs.jna)
    compileOnly(libs.slf4jApi)
    testImplementation(libs.slf4jApi)
}
