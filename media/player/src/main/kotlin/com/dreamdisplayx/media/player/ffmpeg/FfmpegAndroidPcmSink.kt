package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.util.log.DreamLog
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Android [FfmpegPcmSink]，经**反射**驱动 `android.media.AudioTrack`。
 *
 * ## 为什么是反射，而不是 `import android.media.AudioTrack`
 *
 * 这两个平台**互相都没有对方的类**：
 *
 * - Android 运行时没有 `javax.sound.sampled`，桌面 JVM 没有 `android.media.*`。
 * - PojavLauncher / FCL / Zalith 跑的是**桌面 OpenJDK**（没有 `android.jar`），
 *   却又运行在 Android 内核上。也就是说「编译期能引用 android 类」和「运行期真的有
 *   这些类」是两件互不相关的事，而后者在这个最需要它的环境里恰恰为假。
 *
 * 更要紧的是：JVM 在**链接**一个方法时就会解析它签名里的类型。所以
 * 「先 try/catch 再用 `AudioTrack`」是无效写法——异常发生在 catch 生效之前，
 * `NoClassDefFoundError` 会直接穿透到调用方。唯一的办法是全程只出现 `String`
 * 与 `java.lang.reflect` 的类型，让 `android.media.AudioTrack` 这个名字只在
 * `Class.forName` 的字符串里出现。
 *
 * 这与 [FfmpegPcmSinks] 的 `OsInfo.isAndroid` 分支是两回事：那边负责「不要在桌面上
 * 走到这个类」，这里负责「即使走到了，也不要在加载期就炸」。两者都要有。
 *
 * ## 与 libvlc 的 OpenSL ES 路径的关系
 *
 * 之前 Android 的声音完全由 libvlc 的 `opensles_android` aout 承担，而那个路径有个
 * 硬上限：Android 每进程最多 32 个 `AudioTrack`，libvlc 每次替换播放器都要新建一个，
 * 且本项目不能 `release` 被替换的播放器（会触发 VLC 的 JNI TLS 析构崩溃，见
 * AGENTS.md），于是每个被替换的播放器永久占住一个槽位——「先有声，播一阵子就永久
 * 静音」正是撞上这个上限（详 [com.dreamdisplayx.media.player.managers.AudioTrackBudget]）。
 *
 * 本类拥有**恰好一个** `AudioTrack`，全程只 `flush()` 不重建，因此这条路径不吃那个
 * 预算。这也是 FFmpeg 管线能在 Android 上真正接管声音的前提。
 *
 * ## 计数与时钟
 *
 * `getPlaybackHeadPosition()` 返回的是 32 位无符号帧计数，且 `flush()` 会清零它。
 * 所以不能直接用它当「本会话已播字节」——必须逐次轮询累加增量（回绕换算在
 * [AudioTrackSpec.deltaFrames] 里，可单测）。主时钟拿到的就是这份累加值。
 */
internal class FfmpegAndroidPcmSink(
    private val debugLabel: String,
) : FfmpegPcmSink {

    private val logger = DreamLog.of("FfmpegAndroidAudio")

    private companion object {
        /** 44.1 kHz 立体声 S16，与 [FfmpegAudioDecoder.TARGET_RATE] 一致。 */
        const val SAMPLE_RATE = FfmpegAudioDecoder.TARGET_RATE
        const val CHANNELS = FfmpegAudioDecoder.TARGET_CHANNELS
        const val BYTES_PER_FRAME = CHANNELS * 2

        /** 单次写入失败（环满）后的轮询间隔。 */
        const val WRITE_POLL_MS = 2L

        /**
         * 一次 [write] 里因环满而反复等待的总时长上限。
         *
         * 与桌面 sink 同一个理由：解码线程与视频共用一条线程，设备真的停摆时在这里
         * 无限等下去会把画面一起拖死。一秒远超任何正常反压。
         */
        const val WRITE_STALL_GIVE_UP_NS = 1_000_000_000L

        /** 反射查找结果缓存：`Class.forName` 每次调用都有可观开销，而这里每秒都在用。 */
        private val trackClass: Class<*>? by lazy { runCatching { Class.forName("android.media.AudioTrack") }.getOrNull() }
    }

    /** 反射到的方法，首次 [open] 时解析并缓存。 */
    private var writeMethod: Method? = null
    private var playMethod: Method? = null
    private var pauseMethod: Method? = null
    private var stopMethod: Method? = null
    private var flushMethod: Method? = null
    private var headPosMethod: Method? = null
    private var releaseMethod: Method? = null
    private var setVolumeMethod: Method? = null

    @Volatile
    private var track: Any? = null

    @Volatile
    private var opened = false

    /** 串行化对 track 的所有调用：解码线程写、控制线程 flush/轮询。 */
    private val trackLock = Any()

    @Volatile
    private var written = 0L

    /** 上次轮询到的播放头**无符号**帧值，用于累加增量。 */
    private var lastHeadFrames = -1L

    /** 本会话累计已播帧数（跨 `flush()` 由我们自己守住，不依赖设备的清零行为）。 */
    @Volatile
    private var playedFramesAccum = 0L

    @Volatile
    private var gain = 1.0

    override val isOpen: Boolean get() = opened

    override fun open(): Boolean {
        if (opened) return true
        val cls = trackClass ?: run {
            logger.warn("{} android.media.AudioTrack is not on this classpath; no audio", debugLabel)
            return false
        }
        return try {
            val minBuf = runCatching {
                val m = cls.getMethod(
                    "getMinBufferSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                )
                (m.invoke(null, SAMPLE_RATE, AudioTrackSpec.CHANNEL_OUT_STEREO, AudioTrackSpec.ENCODING_PCM_16BIT) as Int)
            }.getOrElse { -1 }

            val bufBytes = AudioTrackSpec.bufferBytes(
                sampleRate = SAMPLE_RATE,
                bytesPerFrame = BYTES_PER_FRAME,
                platformMinBytes = minBuf,
                bufferMs = AudioTrackSpec.bufferMsFromProperty(),
            )
            if (bufBytes <= 0) {
                logger.warn("{} refusing a zero-byte AudioTrack buffer", debugLabel)
                return false
            }

            // 6 参构造器是 API 3 起就有的那个：没有 AudioAttributes / AudioFormat 依赖，
            // 因此在任何 Android 版本（含只有隐藏 API 的运行时）都能解析到。
            val c = cls.getConstructor(
                Int::class.javaPrimitiveType, // streamType
                Int::class.javaPrimitiveType, // sampleRateInHz
                Int::class.javaPrimitiveType, // channelConfig
                Int::class.javaPrimitiveType, // audioFormat
                Int::class.javaPrimitiveType, // bufferSizeInBytes
                Int::class.javaPrimitiveType, // mode
            )
            val instance = c.newInstance(
                AudioTrackSpec.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioTrackSpec.CHANNEL_OUT_STEREO,
                AudioTrackSpec.ENCODING_PCM_16BIT,
                bufBytes,
                AudioTrackSpec.MODE_STREAM,
            )

            writeMethod = cls.getMethod("write", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            playMethod = cls.getMethod("play")
            pauseMethod = cls.getMethod("pause")
            stopMethod = cls.getMethod("stop")
            flushMethod = cls.getMethod("flush")
            headPosMethod = cls.getMethod("getPlaybackHeadPosition")
            releaseMethod = cls.getMethod("release")
            setVolumeMethod = runCatching { cls.getMethod("setVolume", Float::class.javaPrimitiveType) }.getOrNull()

            synchronized(trackLock) {
                track = instance
                written = 0L
                lastHeadFrames = AudioTrackSpec.unsigned(readHeadRaw(instance))
                playedFramesAccum = 0L
            }
            runCatching { invokeNoArg(instance, playMethod) }
                .onFailure { logger.warn("{} AudioTrack.play() failed: {}", debugLabel, it.message) }
            applyGain(instance, gain)
            opened = true
            logger.info(
                "{} AudioTrack opened: {} Hz/{}ch, buffer {} bytes (~{}ms)",
                debugLabel, SAMPLE_RATE, CHANNELS, bufBytes,
                bufBytes / (SAMPLE_RATE * BYTES_PER_FRAME).coerceAtLeast(1) * 1000,
            )
            true
        } catch (t: Throwable) {
            // 没有音频设备不是播放失败：画面必须继续。每次会话只记一行，避免刷日志。
            logger.warn("{} AudioTrack unavailable ({}), continuing without sound", debugLabel, t.message)
            close()
            false
        }
    }

    override fun write(pcm: ByteArray, offset: Int, length: Int): Int {
        val t = track ?: return -1
        if (!opened) return -1
        if (length <= 0) return 0
        if (offset < 0 || offset + length > pcm.size) return -1
        val m = writeMethod ?: return -1

        var done = 0
        var stalledNs = 0L
        try {
            synchronized(trackLock) {
                while (done < length) {
                    val want = (length - done) / BYTES_PER_FRAME * BYTES_PER_FRAME
                    if (want <= 0) break
                    val n = (m.invoke(t, pcm, offset + done, want) as? Int) ?: -1
                    if (n < 0) {
                        logger.warn("{} AudioTrack.write returned {}; muting for this session", debugLabel, n)
                        return -1
                    }
                    if (n == 0) {
                        // 环满：短暂让出 CPU 而不是死等，超时后放弃本块剩余部分。
                        if (stalledNs >= WRITE_STALL_GIVE_UP_NS) break
                        runCatching { Thread.sleep(WRITE_POLL_MS) }
                        stalledNs += WRITE_POLL_MS * 1_000_000L
                        continue
                    }
                    done += n
                    written += n
                    stalledNs = 0L
                    // 轮询播放头，把增量累加进 playedFramesAccum。放在写入循环里而不是
                    // 单独的定时器里：解码线程本来就在这里被反压，不需要第二个线程。
                    pollHead(t)
                }
            }
        } catch (t2: Throwable) {
            logger.warn("{} AudioTrack write failed: {}", debugLabel, t2.message)
            return -1
        }
        return done
    }

    override val writtenBytes: Long get() = written

    override val playedBytes: Long
        get() {
            val t = track ?: return 0L
            pollHead(t)
            return AudioTrackSpec.framesToBytes(playedFramesAccum, BYTES_PER_FRAME)
        }

    override fun flush() {
        val t = track ?: return
        try {
            synchronized(trackLock) {
                runCatching { invokeNoArg(t, flushMethod) }
                written = 0L
                // flush 会把设备的播放头清零；基线必须跟着重建，否则下一次增量会算成
                // 「倒退」而卡在 0 上。已播累计**不清**——它是内容时间轴的锚点。
                lastHeadFrames = AudioTrackSpec.unsigned(readHeadRaw(t))
            }
        } catch (t2: Throwable) {
            logger.warn("{} AudioTrack flush failed: {}", debugLabel, t2.message)
        }
    }

    override fun setVolume(volume: Double) {
        gain = volume.coerceIn(0.0, 2.0)
        track?.let { applyGain(it, gain) }
    }

    override fun close() {
        val t = track
        opened = false
        track = null
        if (t == null) return
        // pause 再 flush 再 release，顺序与 Android 文档建议一致。release 也是这里
        // 唯一会「销毁」设备的地方——本类从不重建 AudioTrack，所以不需要预算。
        runCatching { invokeNoArg(t, pauseMethod) }
            .onFailure { logger.debug("{} AudioTrack.pause() failed: {}", debugLabel, it.message) }
        runCatching { invokeNoArg(t, flushMethod) }
        runCatching { invokeNoArg(t, stopMethod) }
        runCatching { invokeNoArg(t, releaseMethod) }
            .onFailure { logger.warn("{} AudioTrack.release() failed: {}", debugLabel, it.message) }
        synchronized(trackLock) {
            written = 0L
            playedFramesAccum = 0L
            lastHeadFrames = -1L
        }
    }

    // ── 反射辅助 ─────────────────────────────────────────────────────────────

    /** 读原始播放头（帧）。失败返回 0。 */
    private fun readHeadRaw(t: Any): Int =
        runCatching { (headPosMethod?.invoke(t) as? Int) ?: 0 }.getOrDefault(0)

    /**
     * 轮询一次播放头并把增量累加进 [playedFramesAccum]。
     *
     * 加锁由调用方负责（本类所有 track 访问都在 [trackLock] 里）。
     */
    private fun pollHead(t: Any) {
        val raw = readHeadRaw(t)
        if (lastHeadFrames < 0L) {
            lastHeadFrames = AudioTrackSpec.unsigned(raw)
            return
        }
        val delta = AudioTrackSpec.deltaFrames(raw, lastHeadFrames)
        lastHeadFrames = AudioTrackSpec.unsigned(raw)
        if (delta > 0L) playedFramesAccum += delta
    }

    private fun invokeNoArg(t: Any, m: Method?) {
        if (m == null) return
        try {
            m.invoke(t)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun applyGain(t: Any, value: Double) {
        val m = setVolumeMethod ?: return
        runCatching { m.invoke(t, value.toFloat()) }
            .onFailure { logger.debug("{} AudioTrack.setVolume failed: {}", debugLabel, it.message) }
    }
}