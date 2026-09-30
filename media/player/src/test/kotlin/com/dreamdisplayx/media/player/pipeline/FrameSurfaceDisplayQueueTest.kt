package com.dreamdisplayx.media.player.pipeline

import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.player.FrameUploader
import com.dreamdisplayx.api.media.player.FrameUploaderFactory
import com.dreamdisplayx.api.media.player.GpuTextureRef
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the display-time frame queue and the reusable-buffer pool accounting of [FrameSurface];
 * both are pure JVM logic, so they are exercised with a stub uploader instead of a real GPU.
 */
class FrameSurfaceDisplayQueueTest {
    private val ms = 1_000_000L

    private class FakeTexture : GpuTextureRef

    private class FakeUploader : FrameUploader {
        var uploads = 0
        override fun canUpload() = true
        override fun uploadInterleaved(target: GpuTextureRef, src: ByteBuffer, format: FramePixelFormat): Boolean {
            uploads++
            return true
        }

        override fun uploadPlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, src: ByteBuffer) = true
        override fun cleanup() {}
    }

    private fun newSurface(): Pair<FrameSurface, FakeUploader> {
        val uploader = FakeUploader()
        return FrameSurface("test", FrameUploaderFactory { uploader }, FramePixelFormat.BGRA32) to uploader
    }

    private fun frame(size: Int = 64): ByteBuffer = ByteBuffer.allocateDirect(size)

    /** A frame stamped ahead of the clock must wait; once the clock reaches it, it is uploaded. */
    @Test
    fun `a frame stamped in the future waits for its slot`() {
        val (surface, uploader) = newSurface()
        var clock = 0L
        surface.enableDisplaySync({ clock }, 40 * ms)
        surface.publish(frame(), 64, pts = 500 * ms)

        assertTrue(surface.textureFilled(), "A queued frame counts as texture-filled.")
        assertFalse(surface.updateFrame(FakeTexture(), 4, 4, 4, 4), "A frame ahead of the clock must not upload.")
        assertEquals(0, uploader.uploads)

        clock = 500 * ms
        assertTrue(surface.updateFrame(FakeTexture(), 4, 4, 4, 4), "The frame becomes due once the clock reaches it.")
        assertEquals(1, uploader.uploads)
    }

    /** Without display sync a published frame is due at once (the pre-display-sync behavior). */
    @Test
    fun `a frame published without a timestamp is due immediately`() {
        val (surface, uploader) = newSurface()
        surface.publish(frame(), 64)
        assertTrue(surface.updateFrame(FakeTexture(), 4, 4, 4, 4))
        assertEquals(1, uploader.uploads)
        assertFalse(surface.updateFrame(FakeTexture(), 4, 4, 4, 4), "The queue is empty after the pick.")
    }

    /** When several frames are due, the newest wins and the superseded ones are recycled. */
    @Test
    fun `the newest due frame is picked and older ones are dropped`() {
        val (surface, uploader) = newSurface()
        surface.enableDisplaySync({ 0L }, 0L)
        surface.publish(frame(), 64, pts = 0L)
        surface.publish(frame(), 64, pts = 0L)

        assertTrue(surface.updateFrame(FakeTexture(), 4, 4, 4, 4))
        assertEquals(1, uploader.uploads, "One upload for two due frames: the older one is dropped, not shown.")
        assertFalse(surface.updateFrame(FakeTexture(), 4, 4, 4, 4), "The queue drained in that pick.")
    }

    /** A seek/stop flush must leave nothing queued behind. */
    @Test
    fun `clear drops queued frames`() {
        val (surface, _) = newSurface()
        surface.enableDisplaySync({ 0L }, 0L)
        surface.publish(frame(), 64, pts = 0L)
        surface.clear()

        assertFalse(surface.textureFilled())
        assertFalse(surface.updateFrame(FakeTexture(), 4, 4, 4, 4))
    }

    /** The pool cap is a live count: taken buffers must free capacity for later recycles. */
    @Test
    fun `the reusable pool refills after its buffers are taken again`() {
        val (surface, _) = newSurface()
        repeat(3) { round ->
            repeat(10) { surface.recycleFrameBuffer(surface.allocateFrameBuffer(64)) }
            val taken = (1..10).mapNotNull { surface.takeReusableFrameBuffer(64) }
            assertEquals(4, taken.size, "Pool cap per retained buffer, broken on round $round.")
        }
    }
}
