package com.dreamdisplayx.media.player.managers

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.nio.charset.StandardCharsets

/**
 * Low-level JNA binding to the libvlc shared library, mirroring VideoPlayer's [VlcLibrary].
 *
 * The natives are loaded by [LibVlcNativesLoader] (which sets `jna.library.path` and
 * `VLC_PLUGIN_PATH`); this object just calls `Native.load("libvlc", LibVlc::class.java)`.
 */
object LibVlc {

    private val logger = org.slf4j.LoggerFactory.getLogger("DreamDisplaysX/LibVlc")

    // ── Event constants ──────────────────────────────────────────────────────

    const val LIBVLC_MEDIA_PLAYER_PLAYING = 0x104
    const val LIBVLC_MEDIA_PLAYER_PAUSED = 0x105
    const val LIBVLC_MEDIA_PLAYER_STOPPED = 0x106
    const val LIBVLC_MEDIA_PLAYER_END_REACHED = 0x109
    const val LIBVLC_MEDIA_PLAYER_ENCOUNTERED_ERROR = 0x10A
    const val LIBVLC_MEDIA_PLAYER_TIME_CHANGED = 0x10B
    const val LIBVLC_MEDIA_PLAYER_LENGTH_CHANGED = 0x111
    const val LIBVLC_ENDED = 6

    // libvlc_media_slave_type_t (libvlc_media.h)
    const val LIBVLC_MEDIA_SLAVE_TYPE_AUDIO = 1

    private val RV32 = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), '3'.code.toByte(), '2'.code.toByte())

    // ── Singleton libvlc instance ────────────────────────────────────────────

    @Volatile
    private var instance: Pointer? = null

    @Volatile
    private var loadError: Throwable? = null

    @Volatile
    private var loadAttempted = false

    /** Set once before first [ensureLoaded]; enables `--avcodec-hw=any` when true. */
    @Volatile
    var useHwAccel: Boolean = true

    val lib: LibVlcNative by lazy {
        // NOTE: do NOT call ensureLoaded() here — ensureLoaded() itself resolves `lib` while
        // creating the instance, which would deadlock on the lazy initializer. The caller is
        // expected to call ensureLoaded() (via the session manager) before touching `lib`.
        Native.load("libvlc", LibVlcNative::class.java)
    }

    /** Returns the singleton libvlc instance pointer. */
    val libvlcInstance: Pointer get() = instance ?: throw IllegalStateException("LibVLC not loaded")

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (instance != null) return true
        if (loadAttempted && loadError != null) return false
        loadAttempted = true
        try {
            // NativesDownloader.ensure() is called by LibVlcNativesLoader.load() which sets up
            // jna.library.path and the VLC_PLUGIN_PATH system property. The low-level binding must
            // pass the plugin path explicitly to libvlc_new (vlcj does this internally; libvlc does
            // NOT read the Java system property).
            com.dreamdisplayx.media.player.util.LibVlcNativesLoader.load()
            Native.load("libvlc", LibVlcNative::class.java)
            val opts = mutableListOf("--no-video-title-show", "--no-snapshot-preview", "--quiet",
                "--no-keyboard-events", "--no-mouse-events", "--network-caching=300",
                "--file-caching=300", "--live-caching=600", "--audio-filter=scaletempo")
            // Instance-level browser UA so EVERY http request (including the :input-slave audio
            // stream, which is a separate HTTP transaction from the video) carries a browser-shaped
            // User-Agent. Bilibili/YouTube CDNs reject libvlc's default UA, and media-level options
            // only apply to the primary video URL — the merged audio slave would still be 401/403'd.
            opts.add("--http-user-agent=${com.dreamdisplayx.media.player.util.LibVlcMediaOptions.BROWSER_USER_AGENT}")
            // NOTE: low-level video callbacks (libvlc_video_set_callbacks → vmem) are incompatible
            // with hardware-accelerated decoding: libvlc decodes to a GPU texture and never calls
            // the lock callback, which shows audio but no video. VideoPlayer's `--avcodec-hw=any`
            // only works because it falls back to software decoding on most platforms; on a GPU
            // where hw succeeds the video vanishes, so we force software AVCodec here.
            // (libvlc finds its plugins automatically via VLC_PLUGIN_PATH / relative layout;
            //  `--plugin-path` was removed in libvlc 3.0.21 and only produces a warning.)
            instance = libcCreateInstance(opts)
            loadError = null
            return true
        } catch (t: Throwable) {
            loadError = t
            logger.warn("LibVLC low-level load failed: ${t.message}")
            return false
        }
    }

    // ── Error helpers ────────────────────────────────────────────────────────

    fun errmsg(): String = try {
        val msg = lib.libvlc_errmsg()
        if (msg == null) "" else msg.getString(0)
    } catch (_: RuntimeException) { "" }

    /** Sentinel picture token returned by a lock callback when the frame must be dropped. */
    fun dropToken(): Pointer = Pointer.createConstant(0x7FFFFFFFL)

    // ── Media / instance helpers ─────────────────────────────────────────────

    /** Creates a libvlc instance with the given options. */
    private fun libcCreateInstance(options: List<String>): Pointer {
        NativeStringArray(options).use { arr ->
            val ptr = lib.libvlc_new(options.size, arr.pointer())
            if (ptr == null) throw IllegalStateException("libvlc_new returned null: ${errmsg()}")
            return ptr
        }
    }

    /** Creates a libvlc media object from a URL with media-level options. */
    fun createMedia(url: String, options: Array<String>): Pointer {
        val media = lib.libvlc_media_new_location(libvlcInstance, url)
        if (media == null) throw IllegalStateException("libvlc_media_new_location returned null: ${errmsg()}")
        for (opt in options) {
            lib.libvlc_media_add_option(media, opt)
        }
        return media
    }

    /** Helper: create a direct ByteBuffer of a given size and return its Pointer. */
    fun allocateBuffer(size: Int): java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocateDirect(size).order(java.nio.ByteOrder.nativeOrder())

    // ── NativeStringArray ────────────────────────────────────────────────────

    class NativeStringArray(private val values: List<String>) : AutoCloseable {
        private val strings = ArrayList<Memory>(values.size)
        private val pointer = Memory((values.size + 1L) * Native.POINTER_SIZE)

        init {
            for (i in values.indices) {
                val mem = utf8(values[i])
                strings.add(mem)
                pointer.setPointer(i.toLong() * Native.POINTER_SIZE, mem)
            }
            pointer.setPointer(values.size.toLong() * Native.POINTER_SIZE, null)
        }

        fun pointer(): Pointer = pointer
        override fun close() { strings.clear() }
    }

    private fun utf8(s: String): Memory {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        val mem = Memory((bytes.size + 1).toLong())
        mem.write(0, bytes, 0, bytes.size)
        mem.setByte(bytes.size.toLong(), 0)
        return mem
    }

    // ── Callback interfaces ──────────────────────────────────────────────────
    // The Pointer parameters are nullable because libvlc passes a null `opaque` (and null
    // `userData` for events) when we registered the callbacks with a null context. Kotlin would
    // otherwise insert a non-null check on a null native argument and throw an NPE inside the
    // JNA callback trampoline.

    fun interface EventCallback : Callback {
        fun invoke(event: Pointer?, userData: Pointer?)
    }

    fun interface VideoLockCallback : Callback {
        fun invoke(opaque: Pointer?, planes: Pointer?): Pointer?
    }

    fun interface VideoUnlockCallback : Callback {
        fun invoke(opaque: Pointer?, picture: Pointer?, planes: Pointer?)
    }

    fun interface VideoDisplayCallback : Callback {
        fun invoke(opaque: Pointer?, picture: Pointer?)
    }

    fun interface VideoFormatCallback : Callback {
        fun invoke(opaque: PointerByReference?, chroma: Pointer?, width: Pointer?, height: Pointer?, pitches: Pointer?, lines: Pointer?): Int
    }

    fun interface VideoCleanupCallback : Callback {
        fun invoke(opaque: Pointer?)
    }

    fun interface AudioPlayCallback : Callback {
        fun invoke(data: Pointer?, samples: Pointer?, count: Int, pts: Long)
    }

    fun interface AudioPauseCallback : Callback {
        fun invoke(data: Pointer?, pts: Long)
    }

    fun interface AudioResumeCallback : Callback {
        fun invoke(data: Pointer?, pts: Long)
    }

    fun interface AudioFlushCallback : Callback {
        fun invoke(data: Pointer?, pts: Long)
    }

    fun interface AudioDrainCallback : Callback {
        fun invoke(data: Pointer?)
    }

    fun interface AudioSetVolumeCallback : Callback {
        fun invoke(data: Pointer?, volume: Float, mute: Int)
    }

    fun interface AudioSetupCallback : Callback {
        fun invoke(data: PointerByReference?, format: Pointer?, rate: Pointer?, channels: Pointer?): Int
    }

    fun interface AudioCleanupCallback : Callback {
        fun invoke(data: Pointer?)
    }

    // ── Low-level libvlc native API ──────────────────────────────────────────

    interface LibVlcNative : Library {
        // Instance
        fun libvlc_new(argc: Int, argv: Pointer): Pointer
        fun libvlc_release(instance: Pointer)

        // Error
        fun libvlc_errmsg(): Pointer
        fun libvlc_clearerr()

        // Version
        fun libvlc_get_version(): Pointer

        // Media
        fun libvlc_media_new_location(instance: Pointer, url: String): Pointer
        fun libvlc_media_add_option(media: Pointer, option: String)
        fun libvlc_media_release(media: Pointer)

        // Media player
        fun libvlc_media_player_new(instance: Pointer): Pointer
        fun libvlc_media_player_release(player: Pointer)
        fun libvlc_media_player_set_media(player: Pointer, media: Pointer)
        fun libvlc_media_player_play(player: Pointer): Int
        fun libvlc_media_player_stop(player: Pointer)
        fun libvlc_media_player_set_pause(player: Pointer, pause: Int)
        fun libvlc_media_player_is_playing(player: Pointer): Int
        fun libvlc_media_player_is_seekable(player: Pointer): Int
        fun libvlc_media_player_can_pause(player: Pointer): Int
        fun libvlc_media_player_get_time(player: Pointer): Long
        fun libvlc_media_player_set_time(player: Pointer, time: Long)
        fun libvlc_media_player_get_length(player: Pointer): Long
        fun libvlc_media_player_get_state(player: Pointer): Int
        fun libvlc_media_player_get_fps(player: Pointer): Float
        fun libvlc_media_player_set_rate(player: Pointer, rate: Float): Int
        fun libvlc_media_player_get_rate(player: Pointer): Float
        // Slave (merged audio/video) inputs by full URI — added in libvlc 3.0.6; the string-option
        // :input-slave=... mangles URLs containing '&' (query params get truncated), which silently
        // breaks Bilibili DASH audio streams and the master clock with them.
        fun libvlc_media_player_add_slave(player: Pointer, type: Int, uri: String, select: Boolean): Int

        // Audio
        fun libvlc_audio_set_volume(player: Pointer, volume: Int): Int
        fun libvlc_audio_get_volume(player: Pointer): Int
        fun libvlc_audio_get_track_count(player: Pointer): Int
        fun libvlc_audio_get_track(player: Pointer): Int
        fun libvlc_audio_set_track(player: Pointer, track: Int): Int
        fun libvlc_audio_set_callbacks(player: Pointer, play: AudioPlayCallback, pause: AudioPauseCallback,
                                       resume: AudioResumeCallback, flush: AudioFlushCallback,
                                       drain: AudioDrainCallback, opaque: Pointer)
        fun libvlc_audio_set_volume_callback(player: Pointer, setVolume: AudioSetVolumeCallback)
        fun libvlc_audio_set_format_callbacks(player: Pointer, setup: AudioSetupCallback, cleanup: AudioCleanupCallback)

        // Video
        fun libvlc_video_set_callbacks(player: Pointer, lock: VideoLockCallback, unlock: VideoUnlockCallback,
                                        display: VideoDisplayCallback, opaque: Pointer?)
        fun libvlc_video_set_format_callbacks(player: Pointer, setup: VideoFormatCallback, cleanup: VideoCleanupCallback)

        // Events
        fun libvlc_media_player_event_manager(player: Pointer): Pointer?
        fun libvlc_event_attach(eventManager: Pointer, eventType: Int, callback: EventCallback, userData: Pointer?): Int
        fun libvlc_event_detach(eventManager: Pointer, eventType: Int, callback: EventCallback, userData: Pointer?)
    }
}