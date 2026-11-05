package com.dreamdisplayx.platform.client.managers

import com.dreamdisplayx.util.log.DreamLog
import com.dreamdisplayx.api.display.model.property.DisplayRotation
import com.dreamdisplayx.api.display.model.property.DisplayFacing
import com.dreamdisplayx.api.media.service.keys.MediaServices
import com.dreamdisplayx.api.media.model.VideoQuality
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.playback.model.PlaybackMode
import com.dreamdisplayx.api.storage.model.FullDisplayData
import com.dreamdisplayx.core.protocol.common.packets.DisplayInfo
import com.dreamdisplayx.core.services.DisplayStorage
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.managers.DisplayLifecycleManager.MAX_DISPLAY_BLOCKS
import com.dreamdisplayx.platform.client.render.DisplayGeometry
import com.dreamdisplayx.platform.client.render.ValkyrienClientCompat
import com.dreamdisplayx.platform.client.storage.ClientSettingsStore
import com.dreamdisplayx.util.FacingUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import org.joml.Vector3i
import java.util.*

/**
 * Handles client-side display creation, restoration, and render-distance lifecycle.
 */
object DisplayLifecycleManager {
    /** Logger. */
    private val logger = DreamLog.of("DisplayLifecycleManager")

    /** Maximum allowed display dimension, in blocks. */
    private const val MAX_DISPLAY_BLOCKS = 256

    /** Creates or updates a display from a server [DisplayInfo] packet, honoring render distance and size limits. */
    fun handleInfoPacket(packet: DisplayInfo) {
        if (!ClientStateManager.displaysEnabled) return
        if (!isValidDisplaySize(packet.width, packet.height, packet.depth)) {
            logger.warn("Ignoring display ${packet.id}: invalid size ${packet.width} x ${packet.height} x ${packet.depth}.")
            return
        }

        DisplayRegistry.screens[packet.id]?.let {
            DisplayRegistry.markReconfirmed(packet.id)
            it.updateData(packet)
            DisplayRegistry.recordScreen(it)
            return
        }

        val facing = FacingUtil.fromPacket(packet.facing.toByte())
        val mode = if (packet.mode == PlaybackMode.LOCAL.wire && packet.isSync) {
            PlaybackMode.SYNCED
        } else {
            PlaybackMode.fromWire(packet.mode)
        }
        val renderDistance = DisplayScreen.clientRenderDistanceBlocks()

        if (!packet.forced && !packet.virtual) {
            Minecraft.getInstance().player?.let { player ->
                val dist = distanceToScreen(
                    packet.x, packet.y, packet.z,
                    packet.width, packet.height, facing.toDisplayFacing(),
                    player.blockPosition(), packet.depth, packet.shipId,
                )
                if (dist > renderDistance) {
                    cacheUnloadedDisplay(packet, facing, mode, currentDimensionKey())
                    return
                }
            }
        }

        DreamServices.registry.getOrNull(MediaServices.RESOLVER_REGISTRY)?.prefetch(MediaSource.from(packet.url))
        DisplayRegistry.unloadedScreens.remove(packet.id)

        createScreen(
            packet.id, packet.ownerId, Vector3i(packet.x, packet.y, packet.z), facing,
            packet.width, packet.height, packet.url, packet.lang,
            mode, packet.qualityCap, DisplayRotation.fromQuarterTurns(packet.rotation),
            positionNanos = packet.positionNanos,
            currentDimensionKey(), packet.depth, packet.conforming, packet.shipId,
        )
        DisplayRegistry.screens[packet.id]?.virtual = packet.virtual
    }

    /**
     * Stashes an out-of-range [packet] as an unloaded-screen snapshot (viewer's saved volume / quality /
     * etc. merged in, matching a normal [DisplayScreen.toFullDisplayData] capture), so it restores from
     * the local cache instead of needing another server broadcast once the player is back in range.
     */
    private fun cacheUnloadedDisplay(
        packet: DisplayInfo, facing: FacingUtil, mode: PlaybackMode, dimensionKey: String,
    ) {
        val settings = ClientSettingsStore.getSettings(packet.id, DisplayScreen.defaultVolume())
        DisplayRegistry.unloadedScreens[packet.id] = FullDisplayData(
            uuid = packet.id,
            x = packet.x, y = packet.y, z = packet.z,
            facing = facing.toDisplayFacing(),
            width = packet.width, height = packet.height,
            depth = packet.depth, conforming = packet.conforming,
            videoUrl = packet.url, lang = packet.lang,
            volume = settings.volume, quality = settings.quality, brightness = settings.brightness,
            muted = settings.muted, mode = mode, ownerUuid = packet.ownerId,
            currentTimeNanos = settings.savedTimeNanos,
            rotation = DisplayRotation.fromQuarterTurns(packet.rotation).quarterTurns,
            qualityCap = packet.qualityCap,
            dimensionKey = dimensionKey,
            shipId = packet.shipId,
        )
    }

    /** Builds and registers a new [DisplayScreen], applying saved render distance and loading the video. */
    fun createScreen(
        uuid: UUID, ownerUuid: UUID, pos: Vector3i, facingUtil: FacingUtil,
        width: Int, height: Int, code: String, lang: String,
        mode: PlaybackMode, qualityCap: Int, rotation: DisplayRotation = DisplayRotation.NONE,
        positionNanos: Long = 0,
        dimensionKey: String = currentDimensionKey(),
        depth: Int = 1, conforming: Boolean = false, shipId: Long = 0L,
    ) {
        val displayScreen = DisplayScreen(
            uuid, ownerUuid, pos.x(), pos.y(), pos.z(), facingUtil.toDisplayFacing(),
            width, height, mode, qualityCap, rotation, dimensionKey,
            depth.coerceAtLeast(1), conforming, shipId,
        )

        // A server-persisted seek position overrides the local cache, so a restart resumes rather than replays.
        if (positionNanos > 0) displayScreen.savedTimeNanos = positionNanos

        displayScreen.createTexture()
        DisplayRegistry.registerScreen(displayScreen)
        if (code != "") displayScreen.loadVideo(code, lang)

        if (ClientSettingsStore.getSettings(uuid, DisplayScreen.defaultVolume()).pipOpen) {
            displayScreen.activatePipMode()
        }
    }

    fun restoreVisibleUnloadedScreens(playerPos: BlockPos) {
        val dimensionKey = currentDimensionKey()
        val renderDistance = DisplayScreen.clientRenderDistanceBlocks()
        DisplayRegistry.unloadedScreens.values
            .filter { sameDimension(it.dimensionKey, dimensionKey) && distanceToData(it, playerPos) <= renderDistance }
            // The dimension key alone cannot prove ownership: `minecraft:overworld` is identical in
            // every save, and an older snapshot may carry an empty key, which [sameDimension] accepts.
            // The server-authoritative snapshot for the world we are actually in is the real oracle —
            // a display missing from it belongs to a world we left and must not be rebuilt here (it
            // would start decoding, and the new world's displays would have no sound left to share).
            .filter { DisplayStorage.getDisplayData(it.uuid) != null }
            .toList()
            .forEach { data ->
                DisplayRegistry.unloadedScreens.remove(data.uuid)
                restoreScreen(data)
            }
    }

    private fun sameDimension(cached: String, current: String): Boolean =
        cached.isEmpty() || cached == current

    internal fun currentDimensionKey(): String =
        Minecraft.getInstance().level?.let { dimensionKeyOf(it) } ?: ""

    private fun dimensionKeyOf(level: ClientLevel): String =
        //? if >=1.21.11 {
        level.dimension().identifier().toString()
    //?} else
    /*level.dimension().location().toString()*/

    /** Rebuilds a [DisplayScreen] from persisted [data] and re-registers it. */
    private fun restoreScreen(data: FullDisplayData) {
        if (!isValidDisplaySize(data.width, data.height, data.depth)) {
            logger.warn("Skipping cached display ${data.uuid}: invalid size ${data.width}x${data.height}x${data.depth}.")
            DisplayStorage.removeDisplay(data.uuid)
            return
        }

        val displayScreen = DisplayScreen(
            data.uuid, data.ownerUuid, data.x, data.y, data.z, data.facing,
            data.width, data.height, data.mode ?: PlaybackMode.LOCAL,
            qualityCap = data.qualityCap, rotation = DisplayRotation.fromQuarterTurns(data.rotation),
            dimensionKey = data.dimensionKey.ifEmpty { currentDimensionKey() },
            depth = data.depth, conforming = data.conforming, shipId = data.shipId,
        )
        displayScreen.savedTimeNanos = data.currentTimeNanos
        displayScreen.volume = data.volume
        displayScreen.quality = VideoQuality.parse(data.quality)
        displayScreen.brightness = data.brightness
        displayScreen.muted = data.muted

        displayScreen.createTexture()
        DisplayRegistry.screens[displayScreen.uuid] = displayScreen
        DisplayRegistry.recordScreen(displayScreen)

        if (data.videoUrl.isNotEmpty()) {
            displayScreen.loadVideo(data.videoUrl, data.lang)
        }
    }

    /** Distance from [playerPos] to the persisted display [data]'s bounding box. */
    private fun distanceToData(data: FullDisplayData, playerPos: BlockPos) =
        distanceToScreen(
            data.x, data.y, data.z, data.width, data.height, data.facing, playerPos, data.depth, data.shipId,
        )

    /**
     * Shortest distance from [playerPos] to the screen's block bounding box (facing- and depth-aware).
     *
     * A ship-anchored display stores structure-space coordinates, so on a ship that has moved the raw
     * block comparison reports hundreds of blocks and the screen is dropped as "out of range". The
     * observer is therefore mapped back through the ship transform first. When no transform is
     * resolvable (mod absent, ship not streamed in yet) the structure-space result is used, which is
     * exactly the old behaviour — such a display then keeps its old load/unload behaviour rather than
     * vanishing.
     */
    private fun distanceToScreen(
        x: Int, y: Int, z: Int, width: Int, height: Int, facing: DisplayFacing, playerPos: BlockPos,
        depth: Int = 1, shipId: Long = 0L,
    ): Double {
        val safeDepth = depth.coerceAtLeast(1)
        // Discovery does not run here: these censuses decide whether a screen is created at all, so
        // they must stay a pure comparison. Once the screen exists, `DisplayScreen.anchoredShipId`
        // probes for itself and every later census reuses that answer.
        if (shipId <= 0L) {
            return DisplayGeometry.distanceTo(playerPos, x, y, z, width, height, facing, safeDepth)
        }
        return DisplayGeometry.distanceToOnShip(
            playerPos.x.toDouble(), playerPos.y.toDouble(), playerPos.z.toDouble(),
            ValkyrienClientCompat.transformFor(shipId),
            x, y, z, width, height, facing, safeDepth,
        )
    }

    /** True if every dimension is within `1..`[MAX_DISPLAY_BLOCKS]. */
    private fun isValidDisplaySize(width: Int, height: Int, depth: Int = 1): Boolean =
        width in 1..MAX_DISPLAY_BLOCKS && height in 1..MAX_DISPLAY_BLOCKS && depth in 1..MAX_DISPLAY_BLOCKS
}
