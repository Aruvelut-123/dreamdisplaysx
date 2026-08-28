package com.dreamdisplayx.platform.client.render

//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplayx.media.player.process.LibVlcFrameExtractor
import com.dreamdisplayx.media.runtime.security.MediaHostGuard
import com.dreamdisplayx.util.DreamCoroutines
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.mojang.blaze3d.platform.NativeImage
import kotlinx.coroutines.*
import kotlinx.io.IOException
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Real-time scrub-preview thumbnails for the seek bar. Unlike a pre-generated sample sweep, each
 * frame is extracted on demand: when the mouse hovers at a position that is not already cached,
 * a single libvlc extraction is queued for exactly that position and the resulting texture is
 * cached (a small per-video ring), so re-hovering nearby positions hits the cache immediately.
 *
 * Rapid mouse movement is coalesced: only the newest requested position survives per video while
 * an extraction is running, so a fast drag never spawns more than one libvlc player at a time.
 */
object ScrubPreview {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ScrubPreview")

    /** Fixed frame dimensions every extracted frame is encoded to (used by the Vanilla blit rendering). */
    const val FRAME_WIDTH = 256
    const val FRAME_HEIGHT = 144

    /**
     * A cached frame is considered a hit when its timestamp is within this distance of the hover
     * position, so an already-extracted neighbour is reused instead of re-extracting per pixel.
     */
    private const val HIT_TOLERANCE_NANOS = 3_000_000_000L

    /** Max frames kept per video; the farthest from the hover is evicted when exceeded. */
    private const val MAX_FRAMES_PER_KEY = 10

    /** Budget for one extraction. */
    private val EXTRACT_TIMEOUT = 25.seconds

    private class Frame(val timestampNanos: Long, val texture: Identifier)

    /** Sorted (ascending timestamp) frames per video key. */
    private val FRAMES: Cache<String, List<Frame>> = Caffeine.newBuilder()
        .maximumSize(8)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .removalListener<String, List<Frame>> { _, frames, cause ->
            if (cause != RemovalCause.REPLACED) releaseAll(frames)
        }
        .build()

    /** The safe URL to extract from, remembered from the last [request]. */
    private val RAW_URL: Cache<String, String> = Caffeine.newBuilder()
        .maximumSize(16)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build()

    /** Latest requested extraction position per key; coalesces rapid hover/drag movement. */
    private val pendingPositions = ConcurrentHashMap<String, Long>()

    /** Keys with an extraction coroutine currently running (one loop per key). */
    private val extractingKeys = ConcurrentHashMap.newKeySet<String>()

    /**
     * One long-lived, video-only libvlc player per key. The player is created lazily on the first
     * extraction of a video and REUSED for every subsequent scrub frame — the expensive setup
     * (player creation + first-frame decode) happens once per video instead of per hover. Closed
     * when the video is switched/released via [release].
     */
    private val sessions = ConcurrentHashMap<String, LibVlcFrameExtractor.ScrubSession>()

    /** Records the raw URL for [key] so later hovers can extract without re-resolving it. */
    fun request(key: String, rawUrl: String, durationNanos: Long, seekByDecoding: Boolean = false) {
        val safe = runCatching { MediaHostGuard.resolveSafeUrl(rawUrl) }.getOrNull() ?: rawUrl
        RAW_URL.put(key, safe)
    }

    /**
     * Releases the long-lived extractor (and cached frames/textures) for [key]. Call when the video
     * is switched away or unloaded so the native libvlc player is destroyed; the next [frameAt] on
     * this key lazily recreates it.
     */
    fun release(key: String) {
        sessions.remove(key)?.close()
        pendingPositions.remove(key)
        val frames = FRAMES.getIfPresent(key)
        FRAMES.invalidate(key)
        if (frames != null) releaseAll(frames)
    }

    /** Returns the texture of the frame nearest [positionNanos] for [key], extracting it on demand if needed. */
    fun frameAt(key: String, positionNanos: Long): Identifier? {
        val frames = FRAMES.getIfPresent(key)
        val nearest = frames?.minByOrNull { kotlin.math.abs(it.timestampNanos - positionNanos) }
        if (nearest != null && kotlin.math.abs(nearest.timestampNanos - positionNanos) <= HIT_TOLERANCE_NANOS) {
            return nearest.texture
        }
        // Not cached (or too far): queue an extraction for exactly this hover position.
        pendingPositions[key] = positionNanos
        maybeStartExtraction(key)
        // Show the nearest stale frame (or nothing) until the fresh one lands.
        return nearest?.texture
    }

    /** Starts a single per-key extraction loop if one isn't already running. */
    private fun maybeStartExtraction(key: String) {
        if (!extractingKeys.add(key)) return
        val url = RAW_URL.getIfPresent(key) ?: run { extractingKeys.remove(key); return }
        DreamCoroutines.clientIo.launch {
            try {
                // Drain the coalesced queue: extract the newest requested position, then any newer
                // position that arrived while we were busy, until the mouse stops moving.
                while (true) {
                    val pos = pendingPositions.remove(key) ?: break
                    extractAndCache(key, url, pos)
                    if (!pendingPositions.containsKey(key)) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("Scrub preview extraction failed for $key: ${e.message}")
            } finally {
                extractingKeys.remove(key)
            }
        }
    }

    /** Extracts one frame at [offsetNanos] (reusing the per-key player) and adds it to the cache ring. */
    private suspend fun extractAndCache(key: String, url: String, offsetNanos: Long) {
        val session = sessions.computeIfAbsent(key) {
            LibVlcFrameExtractor.ScrubSession(url)
        }
        // Open lazily on first use; a failed open is torn down so the next attempt rebuilds.
        val ok = withTimeoutOrNull(EXTRACT_TIMEOUT) {
            session.open()
        } ?: false
        if (!ok) {
            sessions.remove(key, session)
            session.close()
            return
        }
        val bytes = withTimeoutOrNull(EXTRACT_TIMEOUT) {
            session.extractAt(offsetNanos, FRAME_WIDTH, FRAME_HEIGHT)
        } ?: return
        val id = registerFrame(key, offsetNanos, bytes) ?: return
        addFrame(key, Frame(offsetNanos, id))
    }

    /** Inserts [frame] into the sorted per-key list, evicting the farthest entry beyond [MAX_FRAMES_PER_KEY]. */
    private fun addFrame(key: String, frame: Frame) {
        val prev = FRAMES.getIfPresent(key).orEmpty()
        val next = (prev + frame).sortedBy { it.timestampNanos }
        val trimmed = if (next.size > MAX_FRAMES_PER_KEY) {
            // Drop the frame farthest from the newest (which is also the one just added).
            val newest = next.last()
            next.sortedByDescending { kotlin.math.abs(it.timestampNanos - newest.timestampNanos) }
                .drop(1).sortedBy { it.timestampNanos }
        } else next
        FRAMES.put(key, trimmed)
    }

    /**
     * Decodes [bytes] and registers them as a Minecraft texture on the render thread; blocks the calling
     * (background) thread until registration completes so the frame can be cached before the next hover.
     */
    private fun registerFrame(key: String, timestampNanos: Long, bytes: ByteArray): Identifier? {
        val image = runCatching {
            decode(bytes)
        }.onFailure { e ->
            logger.warn("Scrub frame decode failed for $key@$timestampNanos: ${e.message}.")
        }.getOrNull() ?: return null

        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Identifier? = null
        Minecraft.getInstance().execute {
            runCatching {
                val texKey = "$key@$timestampNanos"
                //? if >=1.21.11 {
                val tex = DynamicTexture({ "scrub-$texKey" }, image)
                //?} else
                /*val tex = DynamicTexture(image)*/
                val id = Identifier.fromNamespaceAndPath("dreamdisplayx", "scrub/${hash(texKey)}")
                Minecraft.getInstance().textureManager.register(id, tex)
                TextureUploadUtil.applyBilinearFilter(tex)
                result = id
            }.onFailure { e ->
                logger.warn("Scrub frame register failed for $key@$timestampNanos: ${e.message}")
                runCatching { image.close() }
            }.also {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    /** Decodes [bytes] (a JPEG) into a GPU-ready RGBA [NativeImage]. */
    @Throws(IOException::class)
    private fun decode(bytes: ByteArray): NativeImage = ByteArrayInputStream(bytes).use { input ->
        val src = ImageIO.read(input) ?: throw IOException("Unsupported scrub frame image (size=${bytes.size}).")
        val w = src.width
        val h = src.height
        val image = NativeImage(NativeImage.Format.RGBA, w, h, false)
        val pixels = src.getRGB(0, 0, w, h, null, 0, w)
        for (i in pixels.indices) {
            val argb = pixels[i]
            val abgr = (argb and 0xFF00FF00.toInt()) or
                    ((argb shl 16) and 0x00FF0000) or
                    ((argb shr 16) and 0xFF)
            val x = i % w
            val y = i / w
            //? if >=1.21.11 {
            image.setPixelABGR(x, y, abgr)
            //?} else
            /*image.setPixelRGBA(x, y, abgr)*/
        }
        image
    }

    /** Unregisters and closes every frame's texture; called when a key is evicted from [FRAMES]. */
    private fun releaseAll(frames: List<Frame>?) {
        if (frames.isNullOrEmpty()) return
        Minecraft.getInstance().execute {
            for (f in frames) runCatching { Minecraft.getInstance().textureManager.release(f.texture) }
        }
    }

    /** Returns a SHA-1 hex digest of [s], falling back to `hashCode` if SHA-1 is unavailable. */
    private fun hash(s: String): String = try {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16)).append(Character.forDigit(b.toInt() and 0xF, 16))
        sb.toString()
    } catch (_: NoSuchAlgorithmException) {
        Integer.toHexString(s.hashCode())
    }
}
