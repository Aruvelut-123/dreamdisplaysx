package com.dreamdisplayx.platform.client.ui

//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.CharacterEvent
//?}
import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.display.service.keys.DisplayServices
import com.dreamdisplayx.api.media.service.keys.MediaServices
import com.dreamdisplayx.api.media.model.VideoQuality
import com.dreamdisplayx.api.media.model.StretchMode
import com.dreamdisplayx.api.media.audio.model.AcousticQuality
import com.dreamdisplayx.api.media.audio.service.keys.AudioAcousticsServices
import com.dreamdisplayx.api.media.search.model.MediaSearchResult
import com.dreamdisplayx.api.playback.model.DisplayAccess
import com.dreamdisplayx.api.playback.model.FullscreenMode
import com.dreamdisplayx.api.playback.model.PlaybackAction
import com.dreamdisplayx.api.playback.model.PlaybackMode
import com.dreamdisplayx.api.playback.service.keys.PlaybackServices
import com.dreamdisplayx.api.runtime.registry.service.get
import com.dreamdisplayx.api.watchparty.service.keys.WatchPartyServices
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.dreamdisplayx.platform.client.popout.PopoutManager
import com.dreamdisplayx.platform.client.render.ScrubPreview
import com.dreamdisplayx.platform.client.storage.ClientSettingsStore
import com.dreamdisplayx.platform.client.storage.CustomVideoStore
import com.dreamdisplayx.platform.client.ui.kit.UiRect
import com.dreamdisplayx.platform.client.ui.kit.UiScreenBase
import com.dreamdisplayx.platform.client.ui.kit.UiText
import com.dreamdisplayx.platform.client.ui.kit.UiTheme
import com.dreamdisplayx.platform.client.ui.kit.drawPanel
import com.dreamdisplayx.platform.client.ui.menu.*
import com.dreamdisplayx.platform.client.ui.widgets.*
import com.dreamdisplayx.platform.client.utils.MinecraftScreenUtil
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * The display configuration screen: video preview with playback controls, the settings rows, and
 * the suggestions panel.
 */
class DisplayMenu private constructor(
    val displayScreen: DisplayScreen,
) : UiScreenBase(Component.translatable("dreamdisplayx.ui.title")) {

    private val openedDuringReplay =
        com.dreamdisplayx.platform.client.render.ReplayModCompat.isReplayActive

    private val replayReadOnly: Boolean
        get() = openedDuringReplay || com.dreamdisplayx.platform.client.render.ReplayModCompat.isReplayActive

    private val modLabel = ModTitleLabel()
    private val popout = DreamServices.registry.get<PopoutManager>()
    private val dropdown = PopoutDropdown(
        onWindow = { popout.openWindow(DisplayId(displayScreen.uuid)) },
        onPip = { popout.openPip(DisplayId(displayScreen.uuid)) },
        onFullscreen = { popout.openFullscreen(DisplayId(displayScreen.uuid), FullscreenMode.STANDARD); onClose() },
        onBorderless = { popout.openFullscreen(DisplayId(displayScreen.uuid), FullscreenMode.IMMERSIVE); onClose() },
    )
    private val audioTrackDropdown = AudioTrackDropdown(
        getTracks = { displayScreen.audioTrackList },
        currentUrl = { displayScreen.currentAudioTrackUrl },
        // Routed through the playback service (client-local, per-viewer) like every other control.
        onSelect = {
            DreamServices.registry.get(PlaybackServices.PLAYBACK)
                .setAudioTrack(DisplayId(displayScreen.uuid), it.url)
        },
    )
    private val subtitleDropdown = SubtitleDropdown(
        getTracks = { displayScreen.subtitleTrackList },
        currentLang = { displayScreen.currentSubtitleLang.takeIf { displayScreen.subtitlesEnabled } },
        onSelect = {
            DreamServices.registry.get(PlaybackServices.PLAYBACK)
                .setSubtitleTrack(DisplayId(displayScreen.uuid), it?.lang)
        },
    )

    private lateinit var volume: ValueSlider
    private lateinit var quality: ValueSlider
    private lateinit var brightness: ValueSlider
    private lateinit var audio3d: ModeSlider<AcousticQuality>
    private lateinit var sync: ModeSlider<PlaybackMode>
    private lateinit var stretch: ModeSlider<StretchMode>
    private lateinit var progress: SeekBar
    private lateinit var suggestions: SuggestionsPanel
    private lateinit var preview: PreviewSection
    private lateinit var settings: SettingsSection
    private lateinit var errorPanel: ErrorPanel
    private lateinit var popoutButton: IconButton
    private lateinit var audioTrackButton: IconButton
    private lateinit var subtitleButton: IconButton

    private var prevQualityListSize = 0
    private var suggestionsRect: UiRect? = null

    override fun init() {
        super.init()
        val ds = displayScreen
        // Playback controls drive the display through the core PlaybackService instead of mutating
        // the DisplayScreen directly, so the UI no longer reaches into the live screen for these actions.
        val displayId = DisplayId(ds.uuid)
        val playback = DreamServices.registry.get(PlaybackServices.PLAYBACK)
        val watchParty = DreamServices.registry.get(WatchPartyServices.WATCH_PARTY)
        val displays = DreamServices.registry.get(DisplayServices.DISPLAY)
        val videoReady = { ds.isVideoStarted && !ds.errored }
        val notErrored = { !ds.errored }

        volume = addUi(
            ValueSlider(
                initial = ds.volume.toDouble(),
                label = { Component.literal("${floor(it * 200).toInt()}%") },
                // Volume's fraction maps to 0-200%, so a 5%-of-displayed-value stop is 0.025 of the fraction.
                step = 0.025,
            ) { playback.setVolume(displayId, it.toFloat()) })
        volume.enabledWhen = videoReady
        volume.visibleWhen = notErrored

        quality = addUi(
            ValueSlider(
                initial = qualityFraction(ds.quality.serialize()),
                label = {
                    when {
                        // Broadcast pins everyone to the highest quality within the cap; show that, not the saved setting
                        ds.qualityCap > 0 -> Component.literal("${broadcastQuality()}p")
                        ds.qualityList.isNotEmpty() -> Component.literal("${qualityFromFraction(it)}p")
                        else -> Component.literal("${ds.quality.serialize()}p")
                    }
                },
                // One stop per available quality, so the handle can only ever rest exactly on a real option
                step = qualityStep(ds.qualityList.size),
                // Commit on release: applying live would restart the decoder on every stop crossed
                // while dragging, which can drop videoReady() mid-drag and freeze the widget.
                live = false,
            ) {
                if (ds.qualityList.isNotEmpty()) playback.setQuality(
                    displayId,
                    VideoQuality.parse(qualityFromFraction(it))
                )
            })
        quality.enabledWhen = { ds.qualityList.isNotEmpty() && ds.canChangeQualityHere }
        quality.visibleWhen = notErrored

        brightness = addUi(
            ValueSlider(
                initial = ds.brightness.toDouble().coerceIn(0.0, 1.0),
                label = { Component.literal("${floor(it * 100).toInt()}%") },
                step = 0.05,
            ) { playback.setBrightness(displayId, it.toFloat()) })
        brightness.enabledWhen = { !ds.isSync || ds.canEdit }
        brightness.visibleWhen = notErrored

        audio3d = addUi(
            ModeSlider(
                modes = AUDIO_3D_MODES,
                initial = ClientStateManager.config.audioAcoustics,
                current = { ClientStateManager.config.audioAcoustics },
                enabledFor = { true },
                label = { Component.translatable(audio3dModeLabel(it)) },
            ) { quality ->
                ClientStateManager.config.audioAcoustics = quality
                ClientStateManager.config.save()
                DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.setGlobalQuality(quality)
            })
        audio3d.visibleWhen = notErrored

        sync = addUi(
            ModeSlider(
                modes = SYNC_MODES,
                initial = ds.effectiveMode,
                current = { ds.effectiveMode },
                enabledFor = {
                    if (ds.watchParty != null) {
                        it == PlaybackMode.LOCAL && ds.canCloseWatchPartyHere
                    } else {
                        it != PlaybackMode.WATCH_PARTY && ds.canSetModeHere
                    }
                },
                label = { Component.translatable(syncModeLabel(it)) },
            ) { mode ->
                when {
                    mode == ds.effectiveMode -> Unit
                    ds.watchParty != null && mode == PlaybackMode.LOCAL -> watchParty.close(displayId)
                    PlaybackMode.isBaseMode(mode) -> playback.setMode(displayId, mode)
                }
            })
        sync.enabledWhen = {
            ds.canSetModeHere || (ds.watchParty != null && ds.canCloseWatchPartyHere)
        }
        sync.visibleWhen = notErrored

        stretch = addUi(
            ModeSlider(
                modes = STRETCH_MODES,
                initial = ds.stretchMode,
                current = { ds.stretchMode },
                enabledFor = { true },
                label = { Component.translatable(stretchModeLabel(it)) },
            ) { mode ->
                if (mode != ds.stretchMode) ds.stretchMode = mode
            })
        stretch.visibleWhen = notErrored

        val stretchReset = addUi(IconButton("refresh") {
            if (ds.stretchMode != StretchMode.LETTERBOX) ds.stretchMode = StretchMode.LETTERBOX
        })
        stretchReset.enabledWhen = { ds.stretchMode != StretchMode.LETTERBOX }
        stretchReset.visibleWhen = notErrored

        val qualityReset = addUi(IconButton("refresh") {
            playback.setQuality(displayId, VideoQuality.DEFAULT)
            quality.value = qualityFraction(VideoQuality.DEFAULT.serialize())
        })
        qualityReset.enabledWhen = { ds.canChangeQualityHere && ds.quality != VideoQuality.DEFAULT }
        qualityReset.visibleWhen = notErrored

        val brightnessReset = addUi(IconButton("refresh") {
            playback.setBrightness(displayId, 1.0f)
            brightness.value = 1.0
        })
        brightnessReset.enabledWhen = { abs(brightness.value - 1.0) > 0.01 }
        brightnessReset.visibleWhen = notErrored

        val audio3dReset = addUi(IconButton("refresh") {
            ClientStateManager.config.audioAcoustics = AUDIO_3D_DEFAULT
            ClientStateManager.config.save()
            DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.setGlobalQuality(AUDIO_3D_DEFAULT)
        })
        audio3dReset.enabledWhen = { ClientStateManager.config.audioAcoustics != AUDIO_3D_DEFAULT }
        audio3dReset.visibleWhen = notErrored

        val syncReset = addUi(IconButton("refresh") {
            if (ds.canSetModeHere) playback.setMode(displayId, PlaybackMode.LOCAL)
        })
        syncReset.enabledWhen = { ds.canSetModeHere && ds.effectiveMode != PlaybackMode.LOCAL }
        syncReset.visibleWhen = notErrored

        val accessReset = addUi(IconButton("refresh") {
            if (ds.canToggleLockHere) displays.setAccess(displayId, ds.defaultAccess)
        })
        accessReset.enabledWhen = { ds.canToggleLockHere && ds.access != ds.defaultAccess }
        accessReset.visibleWhen = { ds.access != null && !ds.errored }

        val lockButton = addUi(
            IconButton(
                icon = { IconButton.modIcon(if (ds.isLocked == true) "lock" else "unlock") },
            ) {
                val locked = ds.isLocked ?: return@IconButton
                displays.setAccess(displayId, if (locked) DisplayAccess.EVERYONE else DisplayAccess.LOCKED)
            })
        lockButton.enabledWhen = { ds.canToggleLockHere }
        lockButton.visibleWhen = { ds.isLocked != null && !ds.errored }

        val muteButton = addUi(
            IconButton(
                icon = { IconButton.modIcon(if (ds.muted) "mute" else "sound") },
            ) { playback.mute(displayId, !ds.muted) })
        muteButton.enabledWhen = videoReady
        muteButton.visibleWhen = notErrored

        popoutButton = addUi(IconButton("popout") {
            if (ds.isPopoutActive) {
                popout.close(displayId)
                dropdown.hide()
            } else {
                dropdown.toggle()
            }
        })
        popoutButton.enabledWhen = { videoReady() && (ds.canPopoutHere || ds.isPopoutActive) }
        popoutButton.visibleWhen = notErrored

        audioTrackButton = addUi(IconButton("lang") { audioTrackDropdown.toggle() })
        audioTrackButton.enabledWhen = { videoReady() && ds.audioTrackList.size > 1 }
        audioTrackButton.visibleWhen = notErrored

        subtitleButton = addUi(IconButton("cc") { subtitleDropdown.toggle() })
        subtitleButton.enabledWhen = { videoReady() && ds.subtitleTrackList.isNotEmpty() }
        subtitleButton.visibleWhen = notErrored

        val danmakuButton = addUi(
            IconButton(
                icon = { IconButton.modIcon(if (ds.danmakuEnabled) "cc" else "mute") },
            ) {
                val next = !ds.danmakuEnabled
                ds.danmakuEnabled = next
                ClientSettingsStore.setDanmakuEnabled(ds.uuid, next)
            })
        danmakuButton.enabledWhen = notErrored
        danmakuButton.visibleWhen = notErrored

        val pauseButton = addUi(
            IconButton(
                icon = { IconButton.modIcon(if (ds.isPaused) "play" else "pause") },
            ) { if (ds.isPaused) playback.play(displayId) else playback.pause(displayId) })
        pauseButton.enabledWhen = { ds.canControlPlayback }
        pauseButton.visibleWhen = notErrored

        progress = addUi(
            SeekBar(
                current = { ds.currentTimeNanos },
                duration = { ds.mediaPlayerDurationNanos },
                previewFrame = { nanos ->
                    if (ds.isLive) null else {
                        val key = ds.videoUrl
                        val rawUrl = ds.scrubPreviewRawUrl
                        val dur = ds.mediaPlayerDurationNanos
                        if (key != null && rawUrl != null) {
                            ScrubPreview.request(key, rawUrl, dur, ds.scrubPreviewSeeksByDecoding)
                        }
                        key?.let { ScrubPreview.frameAt(it, nanos) }
                    }
                },
                waitingLabel = { if (!ds.isVideoStarted) Component.translatable("dreamdisplayx.ui.waiting").string else null },
                scheduleLabel = { scheduleCountdownText() },
                statusLabels = listOf(
                    { Component.translatable("dreamdisplayx.ui.quality_applying").string.takeIf { ds.isApplyingQuality } },
                    { Component.translatable("dreamdisplayx.ui.audio_track_loading").string.takeIf { ds.isSwitchingAudioTrack } },
                ),
            ) { nanos ->
                if (ds.canSeek() && !ds.isLive && ds.canSeekHere) {
                    playback.seek(displayId, (nanos / 1_000_000L).milliseconds)
                }
            })
        progress.enabledWhen = { videoReady() && ds.canSeek() && !ds.isLive && ds.canSeekHere }
        progress.visibleWhen = notErrored

        val retryButton = addUi(IconButton("refresh") {
            playback.retry(displayId) // Local re-resolve; the error panel clears itself once it succeeds
        })
        // Only the error panel places it; keep it hidden in the normal menu so it never strays to (0,0)
        retryButton.visibleWhen = { ds.errored }

        val deleteButton = addUi(
            IconButton(
                icon = { IconButton.modIcon("delete") },
                sprites = IconButton.RED_SPRITES,
            ) {
                displays.delete(displayId)
                onClose()
            })
        deleteButton.enabledWhen = { ds.owner || ds.isAdmin }

        val reportButton = if (ClientStateManager.isReportingEnabled) {
            addUi(
                IconButton(
                    icon = { IconButton.modIcon("report") },
                    sprites = IconButton.RED_SPRITES,
                ) {
                    displays.report(displayId)
                    onClose()
                })
        } else null

        suggestions = addUi(SuggestionsPanel(::onPickSuggested, ds.suggestionsController))
        suggestions.visibleWhen = { !ds.errored && suggestionsRect != null }
        // Locked / Broadcast / Watch party displays only let the owner / admin change the video, so
        // the panel shows an "unavailable" notice to everyone else instead of pickable suggestions.
        suggestions.available = { ds.canSetVideoHere }


        preview =
            PreviewSection(
                ds, muteButton, volume, popoutButton, audioTrackButton, subtitleButton, danmakuButton, pauseButton, progress,
                dropdown, audioTrackDropdown, subtitleDropdown,
            )
        settings = SettingsSection(
            rows = settingsRows(qualityReset, brightnessReset, audio3dReset, syncReset, stretchReset),
            ownerActions = listOf(reportButton, deleteButton, lockButton),
            buttonTooltips = listOf(
                lockButton to {
                    ds.isLocked?.let { locked ->
                        listOf(
                            Component.translatable(if (locked) "dreamdisplayx.button.unlock.tooltip.1" else "dreamdisplayx.button.lock.tooltip.1")
                                .withStyle { it.withColor(ChatFormatting.WHITE).withBold(true) },
                            Component.translatable(if (locked) "dreamdisplayx.button.unlock.tooltip.2" else "dreamdisplayx.button.lock.tooltip.2")
                                .withStyle { it.withColor(ChatFormatting.GRAY) },
                        )
                    }
                },
                deleteButton to { buttonTooltip("dreamdisplayx.button.delete") },
                reportButton to { buttonTooltip("dreamdisplayx.button.report") },
                danmakuButton to { buttonTooltip("dreamdisplayx.button.danmaku") },
            ),
        )
        errorPanel = ErrorPanel(retryButton, deleteButton, reportButton) { ds.mediaError }
    }

    /** Builds the settings rows with their tooltip content. */
    private fun settingsRows(
        qualityReset: IconButton,
        brightnessReset: IconButton, audio3dReset: IconButton, syncReset: IconButton, stretchReset: IconButton,
    ): List<SettingsSection.Row> {
        val ds = displayScreen
        return listOf(
            SettingsSection.Row("dreamdisplayx.button.quality", quality, qualityReset) {
                val tip = mutableListOf(
                    tooltipTitle("dreamdisplayx.button.quality.tooltip.1"),
                    tooltipBody("dreamdisplayx.button.quality.tooltip.2"),
                    Component.literal(""),
                    tooltipValue("dreamdisplayx.button.quality.tooltip.4", qualityFromFraction(quality.value)),
                )
                if ((ds.quality.targetHeight ?: 0) > 1080) {
                    tip.add(
                        Component.translatable("dreamdisplayx.button.quality.tooltip.5")
                            .withStyle { it.withColor(ChatFormatting.YELLOW) },
                    )
                }
                tip
            },
            SettingsSection.Row("dreamdisplayx.button.brightness", brightness, brightnessReset) {
                listOf(
                    tooltipTitle("dreamdisplayx.button.brightness.tooltip.1"),
                    tooltipBody("dreamdisplayx.button.brightness.tooltip.2"),
                    Component.literal(""),
                    tooltipValue("dreamdisplayx.button.brightness.tooltip.3", floor(brightness.value * 100).toInt()),
                )
            },
            SettingsSection.Row("dreamdisplayx.button.audio3d", audio3d, audio3dReset) {
                listOf(
                    tooltipTitle("dreamdisplayx.button.audio3d.tooltip.1"),
                    tooltipBody("dreamdisplayx.button.audio3d.tooltip.2"),
                    Component.literal(""),
                    tooltipModeBullet("dreamdisplayx.mode.audio_off", "dreamdisplayx.button.audio3d.tooltip.3"),
                    tooltipModeBullet("dreamdisplayx.mode.audio_enhanced", "dreamdisplayx.button.audio3d.tooltip.4"),
                    tooltipModeBullet("dreamdisplayx.mode.audio_advanced", "dreamdisplayx.button.audio3d.tooltip.5"),
                    Component.literal(""),
                    tooltipValue(
                        "dreamdisplayx.button.audio3d.tooltip.6",
                        Component.translatable(audio3dModeLabel(audio3d.mode)),
                    ),
                )
            },
            SettingsSection.Row("dreamdisplayx.button.synchronization", sync, syncReset, extraGapBefore = 6) {
                listOf(
                    tooltipTitle("dreamdisplayx.button.synchronization.tooltip.1"),
                    tooltipBody("dreamdisplayx.button.synchronization.tooltip.2"),
                    Component.literal(""),
                    tooltipModeBullet("dreamdisplayx.mode.local", "dreamdisplayx.button.synchronization.tooltip.3"),
                    tooltipModeBullet("dreamdisplayx.mode.synced", "dreamdisplayx.button.synchronization.tooltip.4"),
                    tooltipModeBullet("dreamdisplayx.mode.broadcast", "dreamdisplayx.button.synchronization.tooltip.6"),
                    Component.literal(""),
                    tooltipValue(
                        "dreamdisplayx.button.synchronization.tooltip.5",
                        Component.translatable(syncModeLabel(sync.mode)),
                    ),
                )
            },
            SettingsSection.Row("dreamdisplayx.button.stretch", stretch, stretchReset) {
                listOf(
                    tooltipTitle("dreamdisplayx.button.stretch.tooltip.1"),
                    tooltipBody("dreamdisplayx.button.stretch.tooltip.2"),
                    Component.literal(""),
                    tooltipModeBullet("dreamdisplayx.mode.letterbox", "dreamdisplayx.button.stretch.tooltip.3"),
                    tooltipModeBullet("dreamdisplayx.mode.stretch", "dreamdisplayx.button.stretch.tooltip.4"),
                    tooltipModeBullet("dreamdisplayx.mode.crop", "dreamdisplayx.button.stretch.tooltip.5"),
                    Component.literal(""),
                    tooltipValue(
                        "dreamdisplayx.button.stretch.tooltip.6",
                        Component.translatable(stretchModeLabel(stretch.mode)),
                    ),
                )
            },
        )
    }

    /**
     * "Pause in 4:32" / "Play in 4:32" for [displayScreen]'s pending scheduled action (see
     * [com.dreamdisplayx.platform.server.playback.ScheduledPlaybackManager]), or null when none is
     * pending / it has already elapsed. Re-evaluated every frame against the live wall clock.
     */
    private fun scheduleCountdownText(): String? {
        val at = displayScreen.scheduledStartEpochMillis.takeIf { it > 0 } ?: return null
        val remainingMs = at - System.currentTimeMillis()
        if (remainingMs <= 0) return null
        val key = if (displayScreen.scheduledAction == PlaybackAction.PAUSE.wire) {
            "dreamdisplayx.ui.schedule_pause"
        } else {
            "dreamdisplayx.ui.schedule_play"
        }
        return Component.translatable(key, UiText.formatTime(remainingMs * 1_000_000L)).string
    }

    private fun tooltipTitle(key: String): Component =
        Component.translatable(key).withStyle { it.withColor(ChatFormatting.WHITE).withBold(true) }

    private fun tooltipBody(key: String): Component =
        Component.translatable(key).withStyle { it.withColor(ChatFormatting.GRAY) }

    private fun tooltipValue(key: String, arg: Any): Component =
        Component.translatable(key, arg).withStyle { it.withColor(ChatFormatting.GOLD) }

    /** Bullet line naming a playback mode ([modeKey], e.g. `dreamdisplayx.mode.local`) plus its short [descKey]. */
    private fun tooltipModeBullet(modeKey: String, descKey: String): Component =
        Component.literal("• ").withStyle { it.withColor(ChatFormatting.GRAY) }
            .append(Component.translatable(modeKey).withStyle { it.withColor(ChatFormatting.GRAY) })
            .append(Component.literal(": ").withStyle { it.withColor(ChatFormatting.GRAY) })
            .append(Component.translatable(descKey).withStyle { it.withColor(ChatFormatting.GRAY) })

    /** Two-line white/gray tooltip used by the delete and report buttons. */
    private fun buttonTooltip(prefix: String): List<Component> = listOf(
        tooltipTitle("$prefix.tooltip.1"),
        tooltipBody("$prefix.tooltip.2"),
    )

    /** Requests [info] as the display video and reloads the related list once the intent is sent. */
    private fun onPickSuggested(info: MediaSearchResult) {
        val ds = displayScreen
        if (!ds.canSetVideoHere) return
        DreamServices.registry.get(DisplayServices.DISPLAY).setUrl(DisplayId(ds.uuid), info.getWatchUrl(), ds.lang)

        // A pasted link exists nowhere else, so remember it locally the moment it is used
        if (info.isCustom) {
            CustomVideoStore.remember(info.getWatchUrl(), info.title)
        }
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawScreenBackground(g)
        val ds = displayScreen

        modLabel.draw(g, UiTheme.SCREEN_PADDING, 6)
        BilibiliAccountLabel.draw(g, width, font.lineHeight)
        resyncQualitySlider()
        resyncModeSlider()
        audio3d.syncToCurrent()

        if (ds.errored) {
            dropdown.hide()
            suggestionsRect = null
            errorPanel.render(g, width, height)
            drawChildren(g, mouseX, mouseY, partialTick)
            return
        }

        val layout = MenuLayout.compute(width, height, font.lineHeight)
        suggestionsRect = layout.suggestions

        g.drawPanel(font, layout.preview, Component.translatable("dreamdisplayx.ui.preview").string)
        g.drawPanel(font, layout.settings, Component.translatable("dreamdisplayx.ui.settings").string)
        preview.render(g, layout.preview, mouseX, mouseY)
        settings.render(g, layout.settings, mouseX, mouseY)

        val suggestionsArea = layout.suggestions
        if (suggestionsArea != null) {
            suggestions.visible = true
            suggestions.setVertical(layout.suggestionsVertical)
            suggestions.setCompactCards(false)
            suggestions.place(suggestionsArea)
        } else {
            suggestions.visible = false
        }

        drawChildren(g, mouseX, mouseY, partialTick)
        //? if <1.21.11 {
        suggestions.redrawSortDropdownOnTop(g, mouseX, mouseY)
        //?}
        settings.renderTooltips(g, mouseX, mouseY, toRealX(mouseX), toRealY(mouseY))
    }

    /** Re-syncs the quality slider position when the available quality list (re)appears. */
    private fun resyncQualitySlider() {
        val ds = displayScreen
        val qualityList = ds.qualityList
        if (qualityList.size != prevQualityListSize) {
            prevQualityListSize = qualityList.size
            quality.step = qualityStep(qualityList.size)
            if (qualityList.isNotEmpty()) {
                // In Broadcast the handle should sit on the capped quality, not the user's saved value.
                quality.value = qualityFraction(
                    if (ds.qualityCap > 0) broadcastQuality().toString() else ds.quality.serialize()
                )
            }
        }
    }

    /** Keeps the synchronization mode slider aligned with server echoes and watch-party state. */
    private fun resyncModeSlider() {
        sync.syncToCurrent()
    }

    override fun onMouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (replayReadOnly) return true
        return settings.handleScroll(mouseX.toInt(), mouseY.toInt(), scrollY) ||
            audioTrackDropdown.handleScroll(mouseX.toInt(), mouseY.toInt(), scrollY)
    }

    //? if >=1.21.11 {
    override fun onMouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (replayReadOnly) return true
        val mx = event.x().toInt()
        val my = event.y().toInt()
        if (event.button() == 0 && settings.handleScrollbarPress(mx, my)) return true
        val onPopoutButton = popoutButton.isMouseOver(mx.toDouble(), my.toDouble())
        if (dropdown.visible && event.button() == 0 && !onPopoutButton && dropdown.handleClick(mx, my)) return true
        val onAudioTrackButton = audioTrackButton.isMouseOver(mx.toDouble(), my.toDouble())
        if (audioTrackDropdown.visible && event.button() == 0 && !onAudioTrackButton && audioTrackDropdown.handleClick(
                mx,
                my
            )
        ) return true
        val onSubtitleButton = subtitleButton.isMouseOver(mx.toDouble(), my.toDouble())
        if (subtitleDropdown.visible && event.button() == 0 && !onSubtitleButton && subtitleDropdown.handleClick(mx, my)) return true
        return modLabel.handleClick(mx, my)
    }

    override fun onMouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (replayReadOnly) return true
        return settings.handleScrollbarDrag(event.y().toInt()) ||
            audioTrackDropdown.handleDrag(event.y().toInt())
    }

    override fun onMouseReleased(event: MouseButtonEvent): Boolean {
        if (replayReadOnly) return true
        return settings.handleScrollbarRelease() ||
            audioTrackDropdown.handleRelease() ||
            progress.commitDragIfActive()
    }

    override fun tick() {
        if (openedDuringReplay && !com.dreamdisplayx.platform.client.render.ReplayModCompat.isReplayActive) {
            onClose()
            return
        }
        super.tick()
    }
    //?} else
    /*override fun onMouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (replayReadOnly) return true
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        if (button == 0 && settings.handleScrollbarPress(mx, my)) return true
        val onPopoutButton = popoutButton.isMouseOver(mouseX, mouseY)
        if (dropdown.visible && button == 0 && !onPopoutButton && dropdown.handleClick(mx, my)) return true
        val onAudioTrackButton = audioTrackButton.isMouseOver(mouseX, mouseY)
        if (audioTrackDropdown.visible && button == 0 && !onAudioTrackButton && audioTrackDropdown.handleClick(mx, my)) return true
        val onSubtitleButton = subtitleButton.isMouseOver(mouseX, mouseY)
        if (subtitleDropdown.visible && button == 0 && !onSubtitleButton && subtitleDropdown.handleClick(mx, my)) return true
        return modLabel.handleClick(mx, my)
    }

    override fun onMouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean =
        settings.handleScrollbarDrag(mouseY.toInt()) ||
            audioTrackDropdown.handleDrag(mouseY.toInt())

    override fun onMouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean =
        settings.handleScrollbarRelease() ||
            audioTrackDropdown.handleRelease() ||
            progress.commitDragIfActive()*/


    override fun onClose() {
        if (!com.dreamdisplayx.platform.client.render.ReplayModCompat.isReplayActive) {
            com.dreamdisplayx.platform.client.render.ReplayModCompat.recordAction("close", displayScreen.uuid)
        }
        super.onClose()
    }

    //? if >=1.21.11 {
    override fun keyPressed(event: KeyEvent): Boolean {
        if (replayReadOnly) return true
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (replayReadOnly) return true
        return super.charTyped(event)
    }
    //?} else
    /*override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (replayReadOnly) return true
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (replayReadOnly) return true
        return super.charTyped(codePoint, modifiers)
    }*/

    override fun isPauseScreen(): Boolean = false

    override fun removed() {
        if (::preview.isInitialized) preview.close()
        super.removed()
    }

    /**
     * The menu needs roughly this much logical space for the normal (non-compact) layout — preview and
     * settings side by side on top, suggestions strip below. On smaller windows (e.g. high GUI scale)
     * [UiScreenBase] scales the whole menu down to fit instead of letting panels overflow.
     */
    override fun minContentSize(): Pair<Int, Int> = MIN_CONTENT_W to MIN_CONTENT_H

    /** The highest available quality within Broadcast's cap — what every client is actually pinned to. */
    private fun broadcastQuality(): Int {
        val ds = displayScreen
        val cap = ds.qualityCap
        return ds.qualityList.filter { it <= cap }.maxOrNull() ?: cap
    }

    /** The slider step for [size] evenly spaced quality stops (1 per available option). */
    private fun qualityStep(size: Int): Double = 1.0 / max(1, size - 1)

    /** Maps a quality string (e.g. "720") to its fractional position within the available quality list. */
    private fun qualityFraction(q: String): Double {
        val list = displayScreen.qualityList
        if (list.isEmpty()) return 0.0
        val target = q.replace("p", "").toIntOrNull() ?: 1080
        val closest = list.minByOrNull { abs(target - it) } ?: return 0.0
        return list.indexOf(closest) / max(1, list.size - 1).toDouble()
    }

    /** Maps a fractional slider position back to the nearest quality string from the available list. */
    private fun qualityFromFraction(v: Double): String {
        val list = displayScreen.qualityList
        if (list.isEmpty()) return "144"
        val idx = (v * (list.size - 1)).roundToInt().coerceIn(0, list.size - 1)
        return list[idx].toString()
    }

    companion object {
        /** Minimum logical canvas the normal layout is comfortable in; smaller windows scale down. */
        private const val MIN_CONTENT_W = 640
        private const val MIN_CONTENT_H = 410

        /** Translation key for the compact mode label shown inside the sync slider. */
        private fun syncModeLabel(mode: PlaybackMode): String = when (mode) {
            PlaybackMode.LOCAL -> "dreamdisplayx.mode.local"
            PlaybackMode.SYNCED -> "dreamdisplayx.mode.synced"
            PlaybackMode.WATCH_PARTY -> "dreamdisplayx.mode.watch_party"
            PlaybackMode.BROADCAST -> "dreamdisplayx.mode.broadcast"
        }

        /** The three tiers exposed by the 3D audio slider; BASIC stays an internal-only engine step. */
        private val AUDIO_3D_MODES = listOf(AcousticQuality.OFF, AcousticQuality.ADVANCED, AcousticQuality.ULTRA)

        /** Snaps [value] to the nearest entry in [presets]; falls back to [defaultV] if empty. */
        private fun snapTo(presets: List<Float>, value: Float, defaultV: Float): Float =
            presets.minByOrNull { kotlin.math.abs(it - value) } ?: defaultV

        /** Factory default the 3D audio row's reset button restores. */
        private val AUDIO_3D_DEFAULT = AcousticQuality.ADVANCED

        /** Translation key for the compact mode label shown inside the 3D audio slider. */
        private fun audio3dModeLabel(quality: AcousticQuality): String = when (quality) {
            AcousticQuality.OFF -> "dreamdisplayx.mode.audio_off"
            AcousticQuality.ULTRA -> "dreamdisplayx.mode.audio_advanced"
            else -> "dreamdisplayx.mode.audio_enhanced"
        }

        /** The three sync-mode notches exposed by the playback-mode slider. */
        private val SYNC_MODES = listOf(PlaybackMode.LOCAL, PlaybackMode.SYNCED, PlaybackMode.BROADCAST)

        /** The three stretch-mode notches exposed by the fit slider. */
        private val STRETCH_MODES = listOf(StretchMode.LETTERBOX, StretchMode.STRETCH, StretchMode.CROP)

        /** Translation key for the compact label shown inside the stretch-mode slider. */
        private fun stretchModeLabel(mode: StretchMode): String = when (mode) {
            StretchMode.LETTERBOX -> "dreamdisplayx.mode.letterbox"
            StretchMode.STRETCH -> "dreamdisplayx.mode.stretch"
            StretchMode.CROP -> "dreamdisplayx.mode.crop"
        }

        /** Opens the menu for [displayScreen]. */
        fun open(displayScreen: DisplayScreen) {
            if (!com.dreamdisplayx.platform.client.render.ReplayModCompat.isReplayActive) {
                com.dreamdisplayx.platform.client.render.ReplayModCompat.recordAction("open", displayScreen.uuid)
            }
            MinecraftScreenUtil.setScreen(Minecraft.getInstance(), DisplayMenu(displayScreen))
        }
    }
}
