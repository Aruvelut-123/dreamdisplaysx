package com.dreamdisplayx.media.player.ffmpeg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pins the file-name → JavaCPP key mapping used by [FfmpegNatives].
 *
 * This is the part of the FFmpeg natives path that can be verified without a
 * device: a wrong key does not throw, it silently leaves the library
 * unregistered and playback later dies with "no jniavcodec in
 * java.library.path" on a user's machine. The expected values below were not
 * derived from the naming rules — they were dumped from `Loader.foundLibraries`
 * after a real classpath load of each platform's archive.
 */
class FfmpegNativesKeyTest {

    // ── Android / Linux (ELF) ───────────────────────────────────────────────

    @Test
    fun `unversioned elf libraries keep the plain name`() {
        assertEquals("jniavcodec", FfmpegNatives.libraryKey("libjniavcodec.so"))
        assertEquals("jniavutil", FfmpegNatives.libraryKey("libjniavutil.so"))
        assertEquals("avcodec", FfmpegNatives.libraryKey("libavcodec.so"))
    }

    @Test
    fun `versioned elf libraries keep the dotted suffix`() {
        // Measured key from a real load: "avcodec@.62", not "avcodec@62".
        assertEquals("avcodec@.62", FfmpegNatives.libraryKey("libavcodec.so.62"))
        assertEquals("avutil@.60", FfmpegNatives.libraryKey("libavutil.so.60"))
        assertEquals("swresample@.6", FfmpegNatives.libraryKey("libswresample.so.6"))
        assertEquals("swscale@.9", FfmpegNatives.libraryKey("libswscale.so.9"))
    }

    @Test
    fun `elf companions that are not ffmpeg still key correctly`() {
        // The Linux archives carry ALSA / VA-API / Raspberry Pi userland libs.
        assertEquals("asound@.2", FfmpegNatives.libraryKey("libasound.so.2"))
        assertEquals("va-drm@.2", FfmpegNatives.libraryKey("libva-drm.so.2"))
        assertEquals("vcos", FfmpegNatives.libraryKey("libvcos.so"))
        assertEquals("vchiq_arm", FfmpegNatives.libraryKey("libvchiq_arm.so"))
    }

    // ── macOS (Mach-O) ─────────────────────────────────────────────────────

    @Test
    fun `macho libraries use the trailing version as a suffix`() {
        assertEquals("avcodec@.62", FfmpegNatives.libraryKey("libavcodec.62.dylib"))
        assertEquals("avutil@.60", FfmpegNatives.libraryKey("libavutil.60.dylib"))
        assertEquals("atomic@.1", FfmpegNatives.libraryKey("libatomic.1.dylib"))
    }

    @Test
    fun `macho libraries without a version keep the plain name`() {
        assertEquals("jniavcodec", FfmpegNatives.libraryKey("libjniavcodec.dylib"))
        assertEquals("jniswscale", FfmpegNatives.libraryKey("libjniswscale.dylib"))
    }

    // ── Windows (PE) ───────────────────────────────────────────────────────

    @Test
    fun `windows libraries are not lib prefixed and use a dashed version`() {
        assertEquals("avcodec@.62", FfmpegNatives.libraryKey("avcodec-62.dll"))
        assertEquals("avutil@.60", FfmpegNatives.libraryKey("avutil-60.dll"))
        assertEquals("swresample@.6", FfmpegNatives.libraryKey("swresample-6.dll"))
    }

    @Test
    fun `windows shims and companions key without a leading lib`() {
        assertEquals("jniavcodec", FfmpegNatives.libraryKey("jniavcodec.dll"))
        assertEquals("jniswresample", FfmpegNatives.libraryKey("jniswresample.dll"))
        assertEquals("winpthread@.1", FfmpegNatives.libraryKey("libwinpthread-1.dll"))
    }

    // ── Non-library entries ────────────────────────────────────────────────

    @Test
    fun `the command line tools and json descriptors are skipped`() {
        // The bytedeco archives carry `ffmpeg` / `ffprobe` binaries and
        // reflect-config.json; registering those would be pointless at best.
        assertNull(FfmpegNatives.libraryKey("ffmpeg"))
        assertNull(FfmpegNatives.libraryKey("ffprobe"))
        assertNull(FfmpegNatives.libraryKey("ffmpeg.exe"))
        assertNull(FfmpegNatives.libraryKey("jni-config.json"))
        assertNull(FfmpegNatives.libraryKey("reflect-config.json"))
        assertNull(FfmpegNatives.libraryKey("resource-config.json"))
    }

    @Test
    fun `a directory prefix is ignored`() {
        assertEquals("avcodec@.62", FfmpegNatives.libraryKey("/tmp/ffmpeg/libavcodec.so.62"))
        assertEquals("jniavcodec", FfmpegNatives.libraryKey("C:/natives/jniavcodec.dll"))
    }

    @Test
    fun `a file that only mentions so later in its name is not misparsed`() {
        // ".so" must be the ELF marker, not any occurrence: "config.something" is
        // a descriptor, and a bare ".so"-less name is not a library at all.
        assertNull(FfmpegNatives.libraryKey("config.something"))
        assertNull(FfmpegNatives.libraryKey("lib.so"))
    }
}