package android.os

import java.io.File

/**
 * Minimal stand-in for the Android framework class `android.os.Environment` that
 * VLC-Android's `JNI_OnLoad` requires to exist on the game JVM classpath.
 *
 * The monolithic `libvlc-all` AAR (3.7.5) links VLC-Android's AndroidBridge
 * initialisation directly into `libvlc.so`. Its exported `JNI_OnLoad` starts with
 * `FindClass("android/os/Environment")`; on Pojav-style game JVMs (desktop OpenJDK,
 * no `android.*` framework classes) that lookup throws `NoClassDefFoundError`, and
 * libvlc bails out with `JNI_ERR` BEFORE caching the `java/lang/System.getProperty`
 * jclass/jmethodID that `vlc_getProxyUrl()` later dereferences. The cached globals
 * stay NULL, so when the http access module opens any URL `vlc_getProxyUrl()` calls
 * `CallStaticObjectMethod(env, NULL, NULL, ...)` and crashes the JVM from the inside
 * (`SIGSEGV in libjvm.so`, thread `config_GetGenericDir`).
 *
 * Shipping this class is the same mechanism squi2rel/VideoPlayer uses (their mod ships
 * an `android.os.Environment` stub so their `libvlc_jvm_bridge.so` bridge works); this is
 * an independent implementation that provides exactly the API surface libvlc's
 * `JNI_OnLoad` resolves:
 *
 *  - the class name itself (`FindClass("android/os/Environment")` must succeed),
 *  - the static method `getExternalStoragePublicDirectory(String): java.io.File`
 *    (`GetStaticMethodID` failure aborts `JNI_OnLoad` with `JNI_ERR`),
 *  - the `DIRECTORY_*` static String fields (`GetStaticFieldID` loop; missing fields are
 *    tolerated — libvlc clears the pending exception and caches NULL per slot).
 *
 * Once `FindClass` succeeds the whole AndroidBridge cache is populated (including
 * `java/lang/System` + `getProperty`), `JNI_OnLoad` returns `JNI_VERSION_1_2`, and
 * `vlc_getProxyUrl()` reads `http.proxyHost` via `System.getProperty` — NULL on a game
 * JVM, which VLC treats as "no proxy" (direct connection). The stub is inert: our code
 * never calls it, JNI only resolves it by name.
 */
@Suppress("unused")
object Environment {

    /** Downloaded content root names, resolved lazily by libvlc's AndroidBridge. */
    @JvmField
    val DIRECTORY_MUSIC: String = "Music"

    @JvmField
    val DIRECTORY_PICTURES: String = "Pictures"

    @JvmField
    val DIRECTORY_DOCUMENTS: String = "Documents"

    @JvmField
    val DIRECTORY_DOWNLOADS: String = "Download"

    @JvmField
    val DIRECTORY_MOVIES: String = "Movies"

    @JvmField
    val DIRECTORY_DCIM: String = "DCIM"

    @JvmField
    val DIRECTORY_PODCASTS: String = "Podcasts"

    @JvmField
    val DIRECTORY_RINGTONES: String = "Ringtones"

    @JvmField
    val DIRECTORY_ALARMS: String = "Alarms"

    @JvmField
    val DIRECTORY_NOTIFICATIONS: String = "Notifications"

    /**
     * Public downloads directory. Only the method signature is needed by libvlc's
     * `JNI_OnLoad` (`GetStaticMethodID`); the returned [File] is never used by the
     * pure-JNA pipeline.
     */
    @JvmStatic
    fun getExternalStoragePublicDirectory(@Suppress("UNUSED_PARAMETER") type: String): File =
        File(".")
}