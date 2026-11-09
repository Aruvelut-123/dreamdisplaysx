plugins {
    id("dreamdisplayx.kotlin-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":media:runtime"))
    api(project(":util"))
    api(libs.commonsCompress)
    api(libs.tukaaniXz)
    api(libs.javacpp)
    api(libs.ffmpeg)
    // Bytedeco 预编译 FFmpeg so（JavaCPP）。运行时由 Loader 自动从 artifact 中解压
    // 对应平台的 libavcodec/libavformat 等 .so；Android 上同 artifact 即可，无需按平台
    // 单独声明（否则 libs.ffmpegLinuxX86 等引用会编译失败）。
    // MediaCodec 硬解：Android 端由 JavaCPP 触发 FFmpeg 内置 mediacodec-ndk 后端，
    // 走原生 C API，不依赖 android.* 框架类，Pojav/Zalith 等隔离 JVM 可用。
    // JNA is only needed at compile time for the low-level libvlc binding; Minecraft bundles it at
    // runtime. Declared compileOnly so it is not propagated downstream and does not conflict with
    // NeoForge's strictly-pinned `net.java.dev.jna:jna:5.14.0`.
    compileOnly(libs.jna)
    compileOnly(libs.slf4jApi)
    testImplementation(libs.slf4jApi)
}
