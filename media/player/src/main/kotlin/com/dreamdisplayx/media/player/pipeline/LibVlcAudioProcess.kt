package com.dreamdisplayx.media.player.pipeline

import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.util.concurrent.TimeUnit

/**
 * A minimal [Process] wrapper that exposes a [LibVlcAudioDecoder]'s PCM output as an
 * `InputStream`, so [AudioSink] can consume it without any API change.
 *
 * The decoder writes S16LE PCM into a [PipedInputStream]; this class exposes that stream
 * via [getInputStream]. [getErrorStream] returns an empty stream (no CLI process to drain).
 * [destroy] / [destroyForcibly] delegate to [LibVlcAudioDecoder.kill].
 */
internal class LibVlcAudioProcess(
    private val decoder: LibVlcAudioDecoder,
    private val input: PipedInputStream,
) : Process() {

    @Volatile
    private var destroyed = false

    override fun getOutputStream(): OutputStream =
        throw UnsupportedOperationException("LibVlcAudioProcess does not support stdin")

    override fun getInputStream(): InputStream = input

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline && !destroyed) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return destroyed
    }

    override fun waitFor(): Int {
        // No-op: the decoder is async and this JVM process is never truly "done"
        while (!destroyed) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return 0
            }
        }
        return 0
    }

    override fun exitValue(): Int = if (destroyed) 0 else throw IllegalThreadStateException("Process not destroyed")

    override fun destroy() {
        destroyed = true
        decoder.kill()
    }

    override fun destroyForcibly(): Process = apply {
        destroyed = true
        decoder.kill()
    }

    override fun isAlive(): Boolean = !destroyed
}