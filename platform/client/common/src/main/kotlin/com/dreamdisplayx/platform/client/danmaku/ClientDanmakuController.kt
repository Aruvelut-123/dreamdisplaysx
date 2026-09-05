package com.dreamdisplayx.platform.client.danmaku

import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.media.source.bilibili.BilibiliApi
import com.dreamdisplayx.media.source.bilibili.BiliDanmakuVideo
import com.dreamdisplayx.media.source.bilibili.danmaku.DanmakuEntry
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Per-[DisplayScreen] Bilibili VOD danmaku controller, adapted from squi2rel/VideoPlayer's
 * `ClientDanmakuController`. Runs entirely on the render thread: resolves the current video's
 * Bilibili identity asynchronously, loads 6-minute danmaku segments ahead, and produces a list of
 * [RenderableDanmaku]s whose positions are interpolated by [DanmakuRenderer] as GPU quads.
 *
 * Live danmaku and YouTube live chat are intentionally omitted for now (Bilibili only per project
 * TODO); the data model has placeholder hooks for future extension.
 */
class ClientDanmakuController(private val screen: DisplayScreen) {

    companion object {
        private val logger = LoggerFactory.getLogger("DreamDisplaysX/DanmakuController")

        private const val VIRTUAL_HEIGHT = 360f
        private const val SEGMENT_MS = 360_000L
        private const val MAX_ACTIVE = 512
        private const val FIXED_LANES = 4
        private const val LANE_HEIGHT = 26f
        private const val FIXED_DURATION_MS = 4_000L
        private const val BASE_MIN_ROLLING_DURATION_MS = 6_500L
        private const val BASE_MAX_ROLLING_DURATION_MS = 9_000L
        private const val MIN_ROLLING_DURATION_MS = 3_500L
        private const val MAX_ROLLING_DURATION_MS = 14_000L
        private const val ROLLING_LANE_EMIT_DELAY_MS = 200L
        private val SPEED_MULTIPLIERS = floatArrayOf(1.6f, 1.25f, 1.0f, 0.75f, 0.55f)
        private const val DENSITY_NORMAL = 0
        private const val DENSITY_MORE = 1
        private const val DENSITY_OVERLAP = 2
    }

    // ── state ──

    private val active = ArrayList<ActiveDanmaku>()
    private val vodEntries = ArrayList<DanmakuEntry>()
    private val vodEntryKeys = HashSet<String>()
    private val emittedKeys = HashSet<String>()
    private val loadedSegments = HashSet<Int>()
    private val loadingSegments = HashSet<Int>()

    private var danmakuVideo: BiliDanmakuVideo? = null
    private var sourceTask: CompletableFuture<BiliDanmakuVideo?>? = null
    private var currentVideoKey = ""
    private var nextVodIndex = 0
    private var lastProgress = -1L
    private var topLaneCursor = 0
    private var bottomLaneCursor = 0
    private var animationTime = 0L
    private var lastWallTime = 0L
    private var disposed = false

    // ── config shortcuts ──

    private val config get() = ClientStateManager.config

    private fun enabled(): Boolean = config.danmakuEnabled && screen.danmakuEnabled
    private fun speedMultiplier(): Float = SPEED_MULTIPLIERS[config.danmakuSpeedPreset.coerceIn(0, SPEED_MULTIPLIERS.size - 1)]
    private fun scaleMultiplier(): Float = config.danmakuScalePercent.coerceIn(50, 170) / 100.0f
    private fun densityPreset(): Int =
        if (config.danmakuRollingRangePercent != 100) DENSITY_NORMAL else config.danmakuDensityPreset.coerceIn(DENSITY_NORMAL, DENSITY_OVERLAP)

    private fun canvasWidth(): Float {
        val aspect = screen.width.toFloat() / max(1f, screen.height.toFloat())
        return (VIRTUAL_HEIGHT * aspect).coerceIn(240f, 1280f)
    }

    // ── video identity ──

    private fun videoKey(): String {
        val url = screen.videoUrl ?: return ""
        val source = MediaSource.from(url)
        if (source !is MediaSource.Bilibili) return url
        return url + "|" + (source.part ?: 1)
    }

    private fun isBilibiliVod(): Boolean {
        val url = screen.videoUrl ?: return false
        val source = MediaSource.from(url)
        return source is MediaSource.Bilibili && (source.bvid != null || source.avid != null) && source.roomId == null && source.epId == null && source.seasonId == null
    }

    // ── lifecycle ──

    fun update() {
        if (disposed) return
        val key = videoKey()
        if (key != currentVideoKey) resetForInfo(key)
        advanceAnimationClock()
        updateActive()
        if (!enabled() || !isBilibiliVod() || screen.isLive) {
            stopNetworkAndClear()
            return
        }
        updateSourceTask()
        if (danmakuVideo == null) return
        updateVod()
    }

    fun renderables(): List<RenderableDanmaku> {
        val canvasWidth = canvasWidth()
        val canvasHeight = VIRTUAL_HEIGHT
        val result = ArrayList<RenderableDanmaku>(active.size)
        val op = config.danmakuOpacity.coerceIn(20, 100) / 100.0f
        for (item in active) {
            if (blockedBySettings(item.mode, item.color)) continue
            val duration = durationMs(item, canvasWidth)
            val elapsed = animationTime - item.startTime
            if (elapsed < 0 || elapsed > duration) continue
            val x: Float
            val y: Float
            if (item.rolling) {
                if (item.lane >= rollingLaneCount(item.height)) continue
                val travel = canvasWidth + item.width
                val progress = (elapsed.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)
                x = if (item.leftToRight) -item.width + progress * travel else canvasWidth - progress * travel
                y = 6f + item.lane * LANE_HEIGHT
            } else if (item.fixedBottom) {
                x = max(4f, (canvasWidth - item.width) * 0.5f)
                y = canvasHeight - 8f - (item.lane + 1) * LANE_HEIGHT
            } else {
                x = max(4f, (canvasWidth - item.width) * 0.5f)
                y = 6f + item.lane * LANE_HEIGHT
            }
            if (x + item.width < -0.5f || x > canvasWidth + 0.5f || y + item.height < 0 || y > canvasHeight) continue
            result.add(RenderableDanmaku(item.text, x, y, item.scale, item.color, item.width, item.height, !item.rolling, op))
        }
        return result
    }

    fun dispose() {
        disposed = true
        sourceTask?.cancel(true)
        sourceTask = null
        active.clear()
        vodEntries.clear()
        vodEntryKeys.clear()
        emittedKeys.clear()
        loadedSegments.clear()
        loadingSegments.clear()
    }

    // ── clock & progression ──

    private fun advanceAnimationClock() {
        val now = System.currentTimeMillis()
        if (lastWallTime == 0L) { lastWallTime = now; return }
        val delta = (now - lastWallTime).coerceIn(0L, 100L)
        lastWallTime = now
        if (!screen.paused) animationTime += delta
    }

    private fun playbackProgress(): Long {
        val nanos = screen.currentTimeNanos
        return if (nanos > 0) nanos / 1_000_000 else -1L
    }

    private fun seek(progress: Long) {
        active.clear()
        emittedKeys.clear()
        nextVodIndex = lowerBound(max(0L, progress - 1000))
        lastProgress = progress
        topLaneCursor = 0
        bottomLaneCursor = 0
    }

    private fun updateActive() {
        val cw = canvasWidth()
        active.removeAll { blockedBySettings(it.mode, it.color) || animationTime - it.startTime > durationMs(it, cw) }
    }

    private fun resetForInfo(videoKey: String) {
        currentVideoKey = videoKey
        danmakuVideo = null
        sourceTask?.cancel(true)
        sourceTask = null
        active.clear()
        vodEntries.clear()
        vodEntryKeys.clear()
        emittedKeys.clear()
        loadedSegments.clear()
        loadingSegments.clear()
        nextVodIndex = 0
        lastProgress = -1L
        animationTime = 0L
        lastWallTime = 0L
        topLaneCursor = 0
        bottomLaneCursor = 0
    }

    private fun stopNetworkAndClear() {
        sourceTask?.cancel(true)
        sourceTask = null
        active.clear()
    }

    // ── source resolution ──

    private fun updateSourceTask() {
        if (danmakuVideo != null) return
        val task = sourceTask
        if (task == null) {
            val source = (MediaSource.from(screen.videoUrl ?: "") as? MediaSource.Bilibili) ?: return
            val part = source.part ?: 1
            sourceTask = CompletableFuture.supplyAsync { BilibiliApi.danmakuVideo(source) }
            return
        }
        if (!task.isDone) return
        try {
            danmakuVideo = task.get()
            logger.debug("Resolved danmaku identity for {}: {}.", screen.videoUrl, danmakuVideo)
        } catch (_: Exception) {
            logger.warn("Failed to resolve Bilibili danmaku identity for {}.", screen.videoUrl)
        } finally {
            sourceTask = null
        }
    }

    // ── VOD segments ──

    private fun updateVod() {
        val progress = playbackProgress()
        if (progress < 0) return
        if (lastProgress >= 0 && progress < lastProgress - 1500) seek(progress)
        lastProgress = progress
        val segment = (progress / SEGMENT_MS).toInt() + 1
        loadSegment(segment)
        loadSegment(segment + 1)
        enqueueVodDue(progress)
    }

    private fun loadSegment(index: Int) {
        val video = danmakuVideo ?: return
        if (index <= 0 || loadedSegments.contains(index) || loadingSegments.contains(index)) return
        val expectedKey = currentVideoKey
        loadingSegments.add(index)
        CompletableFuture.supplyAsync { BilibiliApi.fetchDanmakuSegment(video, index) }
            .whenComplete { entries, _ ->
                Minecraft.getInstance().execute {
                    if (disposed || currentVideoKey != expectedKey) return@execute
                    loadingSegments.remove(index)
                    loadedSegments.add(index)
                    addVodEntries(entries ?: emptyList())
                }
            }
    }

    private fun addVodEntries(entries: List<DanmakuEntry>) {
        for (entry in entries) {
            if (!entry.renderable()) continue
            if (vodEntryKeys.add(entry.key())) vodEntries.add(entry)
        }
        vodEntries.sortBy { it.progressMs }
        if (lastProgress >= 0) nextVodIndex = minOf(nextVodIndex, lowerBound(max(0L, lastProgress - 1000)))
    }

    private fun enqueueVodDue(progress: Long) {
        while (nextVodIndex < vodEntries.size) {
            val entry = vodEntries[nextVodIndex]
            if (entry.progressMs > progress + 120) break
            nextVodIndex++
            if (entry.progressMs < progress - 1000) continue
            if (emittedKeys.add(entry.key())) enqueue(entry)
        }
    }

    private fun lowerBound(progress: Long): Int {
        var lo = 0; var hi = vodEntries.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (vodEntries[mid].progressMs < progress) lo = mid + 1 else hi = mid }
        return lo
    }

    // ── enqueue / spawn ──

    private fun enqueue(entry: DanmakuEntry?) {
        if (entry == null || !entry.renderable()) return
        if (blockedBySettings(entry.mode, entry.color)) return
        spawn(entry)
    }

    private fun spawn(entry: DanmakuEntry): Boolean {
        val scale = entry.scale() * scaleMultiplier()
        val text = entry.content
        val metrics = DanmakuTextLayoutCache.measure(text, scale)
        val width = metrics.width
        val height = metrics.height
        val lane: Int
        if (entry.fixedTop()) {
            lane = fixedLane(false)
            if (lane < 0) return false
            topLaneCursor++
        } else if (entry.fixedBottom()) {
            lane = fixedLane(true)
            if (lane < 0) return false
            bottomLaneCursor++
        } else {
            lane = rollingLane(height, width, entry.leftToRight())
            if (lane < 0) return false
        }
        if (active.size >= MAX_ACTIVE) active.removeAt(0)
        active.add(ActiveDanmaku(text, entry.mode, entry.argb(), scale, width, height, lane, animationTime))
        return true
    }

    // ── timing ──

    private fun rollingDuration(canvasWidth: Float, width: Float): Long {
        val widthContribution = minOf(width, canvasWidth * 0.5f)
        val base = ((canvasWidth + widthContribution) * 8.0f).roundToLong().coerceIn(BASE_MIN_ROLLING_DURATION_MS, BASE_MAX_ROLLING_DURATION_MS)
        return (base * speedMultiplier()).roundToLong().coerceIn(MIN_ROLLING_DURATION_MS, MAX_ROLLING_DURATION_MS)
    }

    private fun durationMs(item: ActiveDanmaku, canvasWidth: Float): Long =
        if (item.rolling) rollingDuration(canvasWidth, item.width) else FIXED_DURATION_MS

    // ── lanes ──

    private fun rollingRangeHeight(): Float {
        var percent = when (config.danmakuRollingRangePercent) { 25, 50, 75, 100 -> config.danmakuRollingRangePercent; else -> 50 }
        if (config.danmakuBottomGuard) percent = minOf(percent, 85)
        return VIRTUAL_HEIGHT * percent / 100f
    }

    private fun rollingLaneCount(height: Float): Int {
        val usable = rollingRangeHeight() - 12f - height
        return max(1, floor(usable / LANE_HEIGHT).toInt() + 1)
    }

    private fun rollingLane(height: Float, width: Float, leftToRight: Boolean): Int {
        val laneCount = rollingLaneCount(height)
        val density = densityPreset()
        for (i in 0 until laneCount) {
            val lane = i
            if (!rollingLaneEmitDelayed(lane) && !rollingLaneBlocked(lane, width, leftToRight, density)) return lane
        }
        if (density == DENSITY_OVERLAP) {
            var available = 0
            for (i in 0 until laneCount) { if (!rollingLaneEmitDelayed(i)) available++ }
            if (available > 0) {
                var selected = Random.nextInt(available)
                for (i in 0 until laneCount) {
                    if (!rollingLaneEmitDelayed(i)) {
                        if (selected-- == 0) return i
                    }
                }
            }
        }
        return -1
    }

    private fun rollingLaneEmitDelayed(lane: Int): Boolean =
        active.any { it.rolling && it.lane == lane && (animationTime - it.startTime).let { it in 0 until ROLLING_LANE_EMIT_DELAY_MS } }

    private fun rollingLaneBlocked(lane: Int, width: Float, leftToRight: Boolean, density: Int): Boolean {
        val canvasWidth = canvasWidth()
        val gap = if (density == DENSITY_MORE || density == DENSITY_OVERLAP) max(28f, width * 0.25f) else max(72f, width * 0.75f)
        for (item in active) {
            if (!item.rolling || item.lane != lane) continue
            val duration = durationMs(item, canvasWidth)
            val elapsed = animationTime - item.startTime
            if (elapsed < 0 || elapsed > duration) continue
            val travel = canvasWidth + item.width
            val progress = (elapsed.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)
            val x = if (item.leftToRight) -item.width + progress * travel else canvasWidth - progress * travel
            if (leftToRight) { if (x < gap) return true }
            else { if (x + item.width > canvasWidth - gap) return true }
        }
        return false
    }

    private fun fixedLane(bottom: Boolean): Int {
        val cw = canvasWidth()
        val occupied = BooleanArray(FIXED_LANES)
        for (item in active) {
            if (item.rolling || item.fixedBottom != bottom) continue
            val elapsed = animationTime - item.startTime
            if (elapsed >= 0 && elapsed <= durationMs(item, cw) && item.lane in 0 until FIXED_LANES) occupied[item.lane] = true
        }
        val cursor = if (bottom) bottomLaneCursor else topLaneCursor
        for (i in 0 until FIXED_LANES) { val lane = Math.floorMod(cursor + i, FIXED_LANES); if (!occupied[lane]) return lane }
        return -1
    }

    // ── settings ──

    private fun blockedBySettings(mode: Int, color: Int): Boolean {
        val rolling = mode == 1 || mode == 2 || mode == 3 || mode == 6
        val fixed = mode == 4 || mode == 5
        if (config.danmakuBlockRolling && rolling) return true
        if (config.danmakuBlockFixed && fixed) return true
        if (config.danmakuBottomGuard && mode == 4) return true
        return config.danmakuBlockColored && (color and 0x00FFFFFF) != 0x00FFFFFF
    }

    // ── models ──

    data class RenderableDanmaku(
        val text: String,
        val x: Float,
        val y: Float,
        val scale: Float,
        val color: Int,
        val width: Float,
        val height: Float,
        val fixed: Boolean,
        val opacity: Float,
    )

    private data class ActiveDanmaku(
        val text: String,
        val mode: Int,
        val color: Int,
        val scale: Float,
        val width: Float,
        val height: Float,
        val lane: Int,
        val startTime: Long,
    ) {
        val rolling: Boolean get() = mode == 1 || mode == 2 || mode == 3 || mode == 6
        val leftToRight: Boolean get() = mode == 6
        val fixedTop: Boolean get() = mode == 5
        val fixedBottom: Boolean get() = mode == 4
    }
}