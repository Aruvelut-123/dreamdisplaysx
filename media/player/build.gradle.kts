plugins {
    id("dreamdisplayx.kotlin-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":media:runtime"))
    api(project(":util"))
    api(libs.commonsCompress)
    api(libs.tukaaniXz)
    // JNA is only needed at compile time for the low-level libvlc binding; Minecraft bundles it at
    // runtime. Declared compileOnly so it is not propagated downstream and does not conflict with
    // NeoForge's strictly-pinned `net.java.dev.jna:jna:5.14.0`.
    compileOnly(libs.jna)
    compileOnly(libs.slf4jApi)
    testImplementation(libs.slf4jApi)
}
