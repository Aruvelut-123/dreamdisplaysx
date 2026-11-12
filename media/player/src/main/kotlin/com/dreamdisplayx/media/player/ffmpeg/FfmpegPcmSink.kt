package com.dreamdisplayx.media.player.ffmpeg

/**
 * A device that plays interleaved stereo S16LE PCM at 44.1 kHz.
 *
 * ## Why this is an interface rather than one class
 *
 * The desktop answer is a Java Sound `SourceDataLine`; the Android answer is an
 * `AudioTrack` (or `AAudio`) neither of which can be built on the other platform.
 * Everything above the device — the decoder, the pacing rules, the DSP stage,
 * the master clock — is identical, so the device is the only thing behind an
 * interface. That keeps [AudioSinkPolicy] testable and stops the Android class
 * from ever being loaded on a desktop JVM.
 *
 * ## Contract
 *
 * - [open] is idempotent and cheap to call when already open.
 * - [write] may write fewer bytes than asked: a device with a bounded ring
 *   legitimately accepts a partial buffer. Callers loop.
 * - [writtenBytes] counts bytes *accepted by the device*, never bytes offered.
 *   Pacing compares it against [playedBytes] to size the lead; counting offered
 *   bytes would make a stalled device look like it was buffering normally.
 * - [playedBytes] is the device's own cumulative counter of what has actually
 *   reached the speakers. It never rewinds within one [open] session.
 * - Every method must be safe to call after [close]; they become no-ops rather
 *   than throwing, because a decode thread can outlive a control-thread teardown
 *   by one block and an exception there would be swallowed by the thread anyway.
 */
internal interface FfmpegPcmSink : AutoCloseable {

    /** True once [open] succeeded and [close] has not run. */
    val isOpen: Boolean

    /**
     * Opens the device for the fixed PCM shape.
     *
     * @return false when the platform has no usable output (no sound card, no
     *   audio permission, line unavailable). Playback must continue silently:
     *   a picture without sound is far better than nothing.
     */
    fun open(): Boolean

    /**
     * Writes up to [length] bytes starting at [offset].
     *
     * @return bytes accepted, possibly 0 when the ring is momentarily full, or
     *   -1 when the device is permanently gone (callers stop trying rather than
     *   spin forever on a dead line)
     */
    fun write(pcm: ByteArray, offset: Int, length: Int): Int

    /** Total bytes this device has accepted since [open]. */
    val writtenBytes: Long

    /** Bytes the device reports it has actually played since [open]. */
    val playedBytes: Long

    /** Discards everything queued but not yet played (seek / stop). */
    fun flush()

    /** Applies a gain in 0..2 to subsequent writes, or to the whole stream where supported. */
    fun setVolume(volume: Double)

    /** Releases the device. Repeat calls are safe. */
    override fun close()
}
