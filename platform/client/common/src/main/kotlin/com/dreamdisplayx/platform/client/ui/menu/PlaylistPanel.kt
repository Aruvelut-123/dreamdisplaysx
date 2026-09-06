package com.dreamdisplayx.platform.client.ui.menu

import com.dreamdisplayx.api.playback.model.PlaylistCommandAction
import com.dreamdisplayx.api.playback.model.PlaylistEndBehavior
import com.dreamdisplayx.api.playback.model.PlaylistEnqueuePolicy
import com.dreamdisplayx.core.protocol.common.packets.PlaylistItem
import com.dreamdisplayx.platform.client.managers.PlaylistStateStore
import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplayx.platform.client.ui.drawText
import com.dreamdisplayx.platform.client.ui.kit.UiRect
import com.dreamdisplayx.platform.client.ui.kit.UiTheme
import com.dreamdisplayx.platform.client.ui.kit.UiWidget
import com.dreamdisplayx.platform.client.ui.widgets.IconButton
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component
import java.util.UUID
import kotlin.math.max

/**
 * The playlist panel shown in the menu's "Playlist" tab. Renders the server-authoritative queue
 * ([PlaylistStateStore]), lets the player add a URL, reorder / remove / skip items, and the owner
 * change the two queue policies plus the end-of-queue behavior. Every mutation is a
 * [PlaylistCommand] to the server; the echo snapshot re-renders this panel.
 */
class PlaylistPanel(
    private val displayId: UUID,
    private val isOwnerOrAdmin: () -> Boolean,
) : UiWidget(Component.translatable("dreamdisplayx.ui.playlist")) {

    private val font = Minecraft.getInstance().font
    private val urlBox = EditBox(font, 0, 0, 100, 18, Component.translatable("dreamdisplayx.ui.playlist_add_hint"))
    private val titleBox = EditBox(font, 0, 0, 100, 18, Component.translatable("dreamdisplayx.ui.playlist_title_hint"))
    private val addButton = IconButton("search") { submit() }
    private val nextButton = IconButton("right") {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.NEXT.wire)
    }
    private val clearButton = IconButton("delete") {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.CLEAR.wire)
    }
    private val policyButton = IconButton("lock") { togglePolicy() }
    private val endBehaviorButton = IconButton("pause") { toggleEndBehavior() }
    private val modeButton = IconButton(
        icon = { IconButton.modIcon(if (modeEnabled()) "play" else "pause") },
    ) { toggleMode() }

    /** Scroll offset in pixels over the queue rows. */
    private var scroll = 0

    init {
        urlBox.setMaxLength(512)
        titleBox.setMaxLength(64)
    }

    /** Children that must receive vanilla input / render passes (URL box, buttons). */
    val children: List<net.minecraft.client.gui.components.AbstractWidget>
        get() = listOf(urlBox, titleBox, addButton, nextButton, clearButton, policyButton, endBehaviorButton, modeButton)

    private fun state() = PlaylistStateStore.stateOf(displayId)

    /** Whether playlist mode is on (defaults to on while no snapshot has arrived yet). */
    private fun modeEnabled(): Boolean = state()?.enabled != false

    private fun toggleMode() {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.SET_ENABLED.wire, enabled = !modeEnabled())
    }

    private fun togglePolicy() {
        val s = state() ?: return
        val next = when (PlaylistEnqueuePolicy.fromWire(s.enqueuePolicy)) {
            PlaylistEnqueuePolicy.EVERYONE -> PlaylistEnqueuePolicy.OWNER_APPROVAL
            PlaylistEnqueuePolicy.OWNER_APPROVAL -> PlaylistEnqueuePolicy.OWNER_ONLY
            else -> PlaylistEnqueuePolicy.EVERYONE
        }
        PlaylistStateStore.send(displayId, PlaylistCommandAction.SET_ENQUEUE_POLICY.wire, enqueuePolicy = next.wire)
    }

    private fun toggleEndBehavior() {
        val s = state() ?: return
        val next = when (PlaylistEndBehavior.fromWire(s.endBehavior)) {
            PlaylistEndBehavior.PAUSE -> PlaylistEndBehavior.CONTINUE
            PlaylistEndBehavior.CONTINUE -> PlaylistEndBehavior.LOOP_CURRENT
            else -> PlaylistEndBehavior.PAUSE
        }
        PlaylistStateStore.send(displayId, PlaylistCommandAction.SET_END_BEHAVIOR.wire, endBehavior = next.wire)
    }

    /** Adds [urlBox]'s content to the queue; the title box content is used as its display title. */
    private fun submit() {
        val url = urlBox.value.trim()
        if (url.isEmpty()) return
        PlaylistStateStore.send(
            displayId,
            PlaylistCommandAction.ADD.wire,
            url = url,
            title = titleBox.value.trim(),
        )
        urlBox.value = ""
        titleBox.value = ""
    }

    /** Plays the item at [index] immediately (owner / admin only, enforced server-side). */
    private fun skipTo(item: PlaylistItem) {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.SKIP_TO.wire, itemId = item.itemId)
    }

    private fun remove(item: PlaylistItem) {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.REMOVE.wire, itemId = item.itemId)
    }

    private fun approve(item: PlaylistItem) {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.APPROVE.wire, itemId = item.itemId)
    }

    private fun reject(item: PlaylistItem) {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.REJECT.wire, itemId = item.itemId)
    }

    private fun move(item: PlaylistItem, to: Int) {
        PlaylistStateStore.send(displayId, PlaylistCommandAction.MOVE.wire, itemId = item.itemId, position = to)
    }

    /** Places this panel into [panel] and draws it. Version-neutral, mirroring [SettingsSection.render]. */
    fun render(g: GuiGraphicsCompat, panel: UiRect, mouseX: Int, mouseY: Int) {
        place(panel)
        draw(g, mouseX, mouseY, 0f)
    }

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Vanilla child widgets are drawn through the screen's child list (DisplayMenu adds them);
        // this method paints the panel's own chrome: rows, labels, scrollbar.
        val s = state()

        val innerX = x + UiTheme.PANEL_PADDING_X
        val innerW = width - UiTheme.PANEL_PADDING_X * 2

        // Top bar: mode / next / clear / policy / end-behavior buttons on the right, labels on the left.
        val topY = y + 4
        val btnH = 16
        val btnGap = 2
        endBehaviorButton.place(UiRect(x + width - UiTheme.PANEL_PADDING_X - btnH, topY, btnH, btnH))
        policyButton.place(UiRect(x + width - UiTheme.PANEL_PADDING_X - (btnH + btnGap) * 2, topY, btnH, btnH))
        clearButton.place(UiRect(x + width - UiTheme.PANEL_PADDING_X - (btnH + btnGap) * 3, topY, btnH, btnH))
        nextButton.place(UiRect(x + width - UiTheme.PANEL_PADDING_X - (btnH + btnGap) * 4, topY, btnH, btnH))
        modeButton.place(UiRect(x + width - UiTheme.PANEL_PADDING_X - (btnH + btnGap) * 5, topY, btnH, btnH))

        // Playlist mode state always shows (even before the first snapshot arrives), so the owner can
        // flip it without waiting for a queue echo.
        val modeOn = modeEnabled()
        g.drawText(
            font,
            Component.translatable(
                if (modeOn) "dreamdisplayx.ui.playlist_mode_on" else "dreamdisplayx.ui.playlist_mode_off",
            ).string,
            innerX, topY - 2, if (modeOn) 0xFF7BD389.toInt() else UiTheme.TEXT_DIM, false,
        )

        // Add row: URL box + optional title box + add button, right under the top bar. Placed before
        // the state check so the input row stays put even while the first snapshot is still in flight.
        val addY = topY + btnH + font.lineHeight * 2 + 6
        val boxH = 16
        urlBox.x = innerX
        urlBox.y = addY
        urlBox.width = innerW - (boxH + 2) - 90
        titleBox.x = urlBox.x + urlBox.width + 2
        titleBox.y = addY
        titleBox.width = 88
        addButton.place(UiRect(titleBox.x + titleBox.width + 2, addY, boxH, boxH))

        if (s == null) {
            g.drawText(
                font, Component.translatable("dreamdisplayx.ui.playlist_empty").string,
                innerX, addY + boxH + 8, UiTheme.TEXT_DIM, false,
            )
            return
        }

        val policy = PlaylistEnqueuePolicy.fromWire(s.enqueuePolicy)
        val policyText = Component.translatable(
            when (policy) {
                PlaylistEnqueuePolicy.EVERYONE -> "dreamdisplayx.ui.playlist_policy_everyone"
                PlaylistEnqueuePolicy.OWNER_APPROVAL -> "dreamdisplayx.ui.playlist_policy_approval"
                else -> "dreamdisplayx.ui.playlist_policy_owner"
            },
        )
        val endBehavior = PlaylistEndBehavior.fromWire(s.endBehavior)
        val endText = Component.translatable(
            when (endBehavior) {
                PlaylistEndBehavior.PAUSE -> "dreamdisplayx.ui.playlist_end_pause"
                PlaylistEndBehavior.CONTINUE -> "dreamdisplayx.ui.playlist_end_continue"
                else -> "dreamdisplayx.ui.playlist_end_loop"
            },
        )
        // Two stacked mini-labels on the left under the mode line: policy (top) and end behavior (bottom).
        g.drawText(font, policyText.string, innerX, topY + font.lineHeight - 2, UiTheme.TEXT_DIM, false)
        g.drawText(font, endText.string, innerX, topY + font.lineHeight * 2 - 2, UiTheme.TEXT_DIM, false)

        // Queue rows.
        val rowsTop = rowsTopY()
        val rowH = 18
        val rowCount = s.items.size
        val visibleRows = max(0, (y + height - UiTheme.PANEL_PADDING_Y - rowsTop) / rowH)
        val maxScroll = max(0, rowCount - visibleRows)
        scroll = scroll.coerceIn(0, maxScroll)
        val playing = s.currentIndex

        for (vi in 0 until visibleRows) {
            val index = vi + scroll
            if (index >= rowCount) break
            val item = s.items[index]
            val rowY = rowsTop + vi * rowH
            val rowRect = UiRect(innerX, rowY, innerW, rowH - 2)

            val isPlaying = index == playing
            val bgColor = when {
                isPlaying -> UiTheme.ACTIVE_ROW_FILL
                item.pending -> 0x30A0A000.toInt()
                rowRect.contains(mouseX, mouseY) -> UiTheme.HOVER_FILL
                else -> 0
            }
            if (bgColor != 0) g.fill(rowRect.x, rowRect.y, rowRect.right, rowRect.bottom, bgColor)

            val label = when {
                isPlaying -> "▶ "
                item.pending -> "⏳ "
                else -> ""
            } + (item.title.ifBlank { item.url }).take(60)
            g.drawText(font, label, rowRect.x + 2, rowRect.y + 4, if (item.pending) UiTheme.TEXT_DIM else UiTheme.TEXT_PRIMARY, false)

            // Requester tag.
            val byText = Component.translatable("dreamdisplayx.ui.playlist_by").string
            val tagW = font.width(byText + "…")
            g.drawText(
                font, byText + item.requesterId.toString().take(8),
                rowRect.right - tagW - 46, rowRect.y + 4, UiTheme.TEXT_DIM, false,
            )

            // Per-row actions: approve / reject for pending rows (owner), remove for the rest.
            val actionIcon = when {
                item.pending -> "check"
                else -> "cross"
            }
            val canAct = if (item.pending) isOwnerOrAdmin() || item.requesterId == clientPlayerId()
            else isOwnerOrAdmin() || item.requesterId == clientPlayerId()
            if (canAct && rowRect.contains(mouseX, mouseY)) {
                val ax = rowRect.right - 16
                if (item.pending) {
                    // Approve on the left of reject.
                    g.drawText(font, "✓", ax - 14, rowRect.y + 4, 0xFF55FF55.toInt(), false)
                    g.drawText(font, "✕", ax, rowRect.y + 4, 0xFFFF5555.toInt(), false)
                    hoveredPending = item
                    hoveredRemoveItem = null
                } else {
                    g.drawText(font, "✕", ax, rowRect.y + 4, 0xFFFF5555.toInt(), false)
                    hoveredRemoveItem = item
                    hoveredPending = null
                }
            }
        }

        // Pending rows also render drag arrows for the owner (simple move up / down).
        drawScrollbar(g, innerX + innerW + 2, rowsTop, (visibleRows * rowH).coerceAtMost(height), rowCount, maxScroll)

        // Keep vanilla children visible only when this panel is on screen.
        val visible = width > 0 && height > 0
        urlBox.visible = visible
        titleBox.visible = visible
        addButton.visible = visible
        nextButton.visible = visible
        clearButton.visible = visible
        policyButton.visible = visible
        endBehaviorButton.visible = visible
    }

    private var hoveredRemoveItem: PlaylistItem? = null
    private var hoveredPending: PlaylistItem? = null

    private fun clientPlayerId(): UUID =
        Minecraft.getInstance().player?.uuid ?: UUID(0L, 0L)

    /** Simple track-only scrollbar. */
    private fun drawScrollbar(g: GuiGraphicsCompat, barX: Int, top: Int, viewH: Int, count: Int, maxScroll: Int) {
        if (maxScroll <= 0) return
        g.fill(barX, top, barX + 3, top + viewH, UiTheme.SCROLLBAR_TRACK)
        val len = max(14, (viewH.toFloat() * viewH / (count * 18)).toInt())
        val start = top + (scroll.toFloat() / maxScroll * (viewH - len)).toInt()
        g.fill(barX, start, barX + 3, start + len, UiTheme.SCROLLBAR_THUMB)
    }

    /** Click handling in virtual coordinates; returns true when consumed. */
    fun handleClick(mx: Int, my: Int): Boolean {
        val s = state() ?: return false
        // Per-row action buttons (same header geometry as [draw]).
        val rowsTop = rowsTopY()
        val rowH = 18
        val idx = (my - rowsTop) / rowH + scroll
        if (idx in s.items.indices) {
            val item = s.items[idx]
            val innerX = x + UiTheme.PANEL_PADDING_X
            val innerW = width - UiTheme.PANEL_PADDING_X * 2
            val rowRect = UiRect(innerX, rowsTop + (idx - scroll) * rowH, innerW, rowH - 2)
            if (rowRect.contains(mx, my)) {
                val ax = rowRect.right - 16
                if (item.pending && mx >= ax - 14 && mx <= ax - 4) {
                    if (isOwnerOrAdmin()) approve(item) else return false
                    return true
                }
                if (item.pending && mx >= ax && mx <= ax + 10) {
                    if (isOwnerOrAdmin() || item.requesterId == clientPlayerId()) reject(item)
                    return true
                }
                if (!item.pending && mx >= ax && mx <= ax + 10) {
                    if (isOwnerOrAdmin() || item.requesterId == clientPlayerId()) remove(item)
                    return true
                }
                // Plain click elsewhere on the row: owner / admin skips to it.
                if (isOwnerOrAdmin()) {
                    skipTo(item)
                    return true
                }
            }
        }
        return false
    }

    /** Mouse-wheel scroll; returns true when consumed. */
    fun handleScroll(mouseY: Int, scrollY: Double): Boolean {
        val rowsTop = rowsTopY()
        if (mouseY < rowsTop || mouseY > y + height) return false
        scroll = (scroll - scrollY.toInt()).coerceAtLeast(0)
        return true
    }

    /** Top of the queue rows, matching the header geometry in [draw]. */
    private fun rowsTopY(): Int {
        val topY = y + 4
        val btnH = 16
        val addY = topY + btnH + font.lineHeight * 2 + 6
        return addY + 16 + 6
    }

    /** Focuses the URL box when the tab opens. */
    fun focusInput() {
        urlBox.setFocused(true)
        urlBox.setValue("")
    }

    /** True when either text box currently holds keyboard focus. */
    fun isTyping(): Boolean = urlBox.isFocused || titleBox.isFocused
}
