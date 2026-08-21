package com.dreamdisplayx.platform.client.ui.menu

import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplayx.platform.client.ui.drawText
import com.dreamdisplayx.platform.client.ui.kit.UiRect
import com.dreamdisplayx.platform.client.ui.kit.UiTheme
import com.dreamdisplayx.platform.client.ui.kit.UiWidget
import com.dreamdisplayx.platform.client.ui.widgets.IconButton
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The settings panel of the display menu: labeled rows (volume, render distance, quality, brightness, sync,
 * ...), each with a control and a reset button.
 *
 * When the rows do not fit in the panel height, the row area becomes vertically scrollable (mouse wheel
 * and a draggable scrollbar). The panel title and the owner action buttons stay pinned.
 */
class SettingsSection(
    private val rows: List<Row>,
    private val ownerActions: List<IconButton?>,
    private val buttonTooltips: List<Pair<IconButton?, () -> List<Component>?>>,
) {
    /**
     * One settings row: a translated label on the left, the control and its reset button on the
     * right, and a tooltip shown when the label is hovered.
     *
     * @param extraGapBefore additional vertical gap above this row (the sync row is set apart).
     */
    class Row(
        val labelKey: String,
        val control: UiWidget,
        val reset: IconButton,
        val extraGapBefore: Int = 0,
        val tooltip: () -> List<Component>,
    ) {
        internal var labelHover: UiRect? = null
        /** The row's current on-screen rect (accounting for scroll), used for hit-testing. */
        internal var rowRect: UiRect? = null
    }

    // ── Scroll state ──────────────────────────────────────────────────────────────────────────────

    /** Current vertical scroll offset in pixels inside the row area. */
    private var scrollOffset = 0

    /** Maximum scroll offset; > 0 means the row area overflows and scrolling is active. */
    private var maxScroll = 0

    /** Vertical bounds of the scrollable row area (panel-local, in virtual pixels). */
    private var areaTop = 0
    private var areaBottom = 0

    /** True while the user is dragging the scrollbar thumb. */
    private var draggingThumb = false

    /** Scrollbar thumb geometry for hit-testing / dragging, populated during [render]. */
    private var thumbStart = 0
    private var thumbLen = 0
    private var barX = 0

    /** Horizontal scissor bounds of the row area, populated during [render]. */
    private var clipLeft = 0
    private var clipRight = 0

    /** Draws all rows into [panel] and places the owner action buttons in its bottom-right corner. */
    fun render(g: GuiGraphicsCompat, panel: UiRect, mouseX: Int, mouseY: Int) {
        val font = Minecraft.getInstance().font
        val innerX = panel.x + UiTheme.PANEL_PADDING_X
        val innerW = panel.w - UiTheme.PANEL_PADDING_X * 2
        val titleH = panel.y + UiTheme.PANEL_PADDING_Y + font.lineHeight + 6

        // Reserve a shared label column as wide as the widest label so every control gets the same
        // width and left edge, instead of each being squeezed by its own label length.
        val labelColW = rows.maxOf { font.width(Component.translatable(it.labelKey)) }

        // Row area spans from below the title up to the owner-action row at the bottom.
        areaTop = titleH
        areaBottom = panel.bottom - UiTheme.PANEL_PADDING_Y - UiTheme.CONTROL_BUTTON - 6
        val areaH = max(0, areaBottom - areaTop)

        val contentH = rows.sumOf { it.extraGapBefore + UiTheme.ROW_H } +
            UiTheme.ROW_GAP * (rows.size - 1)
        maxScroll = max(0, contentH - areaH)
        scrollOffset = scrollOffset.coerceIn(0, maxScroll)
        clipLeft = panel.x + UiTheme.PANEL_PADDING_X
        clipRight = panel.right - UiTheme.PANEL_PADDING_X

        var rowY = titleH - scrollOffset
        for (row in rows) {
            rowY += row.extraGapBefore
            renderRow(g, row, innerX, rowY, innerW, labelColW, areaTop, areaBottom)
            rowY += UiTheme.ROW_H + UiTheme.ROW_GAP
        }

        drawScrollbar(g, panel, areaTop, areaBottom, areaH, mouseY)
        placeOwnerActions(panel)
    }

    /** Draws one row's background and label, and places its control and reset button. */
    private fun renderRow(
        g: GuiGraphicsCompat,
        row: Row,
        x: Int,
        y: Int,
        w: Int,
        labelColW: Int,
        clipTop: Int,
        clipBottom: Int,
    ) {
        val font = Minecraft.getInstance().font

        // Rows fully outside the visible area are moved off-screen so drawChildren never
        // paints them; visible rows are placed at their correct scroll position.
        val visible = y + UiTheme.ROW_H > clipTop && y < clipBottom
        val placeY = if (visible) y else -9999
        var rightEdge = x + w
        row.reset.place(UiRect(rightEdge - UiTheme.RESET_W, placeY, UiTheme.RESET_W, UiTheme.ROW_H))
        rightEdge -= UiTheme.RESET_W + 4
        val controlW = min(UiTheme.CONTROL_W, max(60, rightEdge - (x + 6 + labelColW + 8)))
        row.control.place(UiRect(rightEdge - controlW, placeY, controlW, UiTheme.ROW_H))

        if (!visible) {
            row.labelHover = null
            return
        }

        g.fill(x, y, x + w, y + UiTheme.ROW_H, UiTheme.ROW_BG)
        row.rowRect = UiRect(x, y, w, UiTheme.ROW_H)

        val label = Component.translatable(row.labelKey)
        val textY = y + UiTheme.ROW_H / 2 - font.lineHeight / 2
        g.drawText(font, label, x + 6, textY, UiTheme.TEXT_PRIMARY, false)
        row.labelHover = UiRect(x + 6, textY, font.width(label), font.lineHeight)
    }

    /** Draws the vertical scrollbar along the right edge of the panel when rows overflow. */
    private fun drawScrollbar(
        g: GuiGraphicsCompat,
        panel: UiRect,
        top: Int,
        bottom: Int,
        viewH: Int,
        mouseY: Int,
    ) {
        if (maxScroll <= 0) {
            draggingThumb = false
            thumbLen = 0
            return
        }
        val barW = 3
        barX = panel.right - UiTheme.PANEL_PADDING_X + 2
        val content = maxScroll + viewH
        g.fill(barX, top, barX + barW, bottom, UiTheme.SCROLLBAR_TRACK)
        val len = max(14, (viewH.toFloat() / content * viewH).toInt())
        val start = top + (scrollOffset.toFloat() / maxScroll * (viewH - len)).toInt()
        //? if >=1.21.11 {
        val hovered = mouseY >= start && mouseY <= start + len
        g.fill(barX, start, barX + barW, start + len, if (hovered) 0xFFA0A0A0.toInt() else UiTheme.SCROLLBAR_THUMB)
        //?} else
        /*g.fill(barX, start, barX + barW, start + len, UiTheme.SCROLLBAR_THUMB)*/
        thumbStart = start
        thumbLen = len
    }

    /** True when ([mx], [my]) is over the scrollbar thumb or its track. */
    fun overScrollbar(mx: Int, my: Int): Boolean {
        if (maxScroll <= 0 || thumbLen <= 0) return false
        return mx >= barX - 1 && mx <= barX + 4 && my >= areaTop && my <= areaBottom
    }

    /** Handles a mouse-wheel scroll over the row area. Returns true if consumed. */
    fun handleScroll(mouseX: Int, mouseY: Int, scrollY: Double): Boolean {
        if (maxScroll <= 0) return false
        if (mouseY < areaTop || mouseY > areaBottom) return false
        if (mouseX < 0 || mouseX > barX + 4) return false
        scrollOffset = (scrollOffset - scrollY.toInt() * SCROLL_STEP).coerceIn(0, maxScroll)
        return true
    }

    /** Handles a mouse press on the scrollbar; starts a thumb drag. Returns true if consumed. */
    fun handleScrollbarPress(mx: Int, my: Int): Boolean {
        if (maxScroll <= 0 || thumbLen <= 0 || !overScrollbar(mx, my)) return false
        if (my >= thumbStart && my <= thumbStart + thumbLen) {
            draggingThumb = true
            return true
        }
        // Track click: jump the thumb so its centre lands on the cursor.
        scrollFromPos(my)
        draggingThumb = true
        return true
    }

    /** Handles a scrollbar thumb drag. Returns true if consumed. */
    fun handleScrollbarDrag(my: Int): Boolean {
        if (!draggingThumb) return false
        scrollFromPos(my)
        return true
    }

    /** Ends a scrollbar thumb drag. Returns true if a drag was active. */
    fun handleScrollbarRelease(): Boolean {
        val was = draggingThumb
        draggingThumb = false
        return was
    }

    /** Maps a cursor [pos] along the scroll axis to [scrollOffset], centering the thumb on it. */
    private fun scrollFromPos(pos: Int) {
        val travel = areaBottom - areaTop - thumbLen
        if (travel <= 0) {
            scrollOffset = 0
            return
        }
        val rel = (pos - areaTop - thumbLen / 2.0).coerceIn(0.0, travel.toDouble())
        scrollOffset = (rel / travel * maxScroll).roundToInt().coerceIn(0, maxScroll)
    }

    /** Places the owner action buttons right-to-left along the panel's bottom-right corner. */
    private fun placeOwnerActions(panel: UiRect) {
        val btn = UiTheme.CONTROL_BUTTON
        var rightEdge = panel.right - UiTheme.PANEL_PADDING_X
        val yEdge = panel.bottom - UiTheme.PANEL_PADDING_Y - btn
        for (b in ownerActions) {
            if (b == null || !b.visible) continue
            b.place(UiRect(rightEdge - btn, yEdge, btn, btn))
            rightEdge -= btn + 4
        }
    }

    /**
     * Renders the tooltip of whichever row label or action button is hovered. Hit-testing uses the
     * virtual ([mouseX], [mouseY]) coordinates; the tooltip is anchored at the real ([anchorX],
     * [anchorY]) coordinates because deferred tooltips render unscaled, outside the menu's transform.
     */
    fun renderTooltips(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, anchorX: Int, anchorY: Int) {
        val font = Minecraft.getInstance().font
        for (row in rows) {
            if (row.labelHover?.contains(mouseX, mouseY) == true) {
                renderTooltip(g, row.tooltip(), anchorX, anchorY)
            }
        }
        for ((button, tooltip) in buttonTooltips) {
            if (button == null || !button.visible) continue
            if (mouseX >= button.x && mouseX < button.x + button.width &&
                mouseY >= button.y && mouseY < button.y + button.height
            ) {
                tooltip()?.let { renderTooltip(g, it, anchorX, anchorY) }
            }
        }
    }

    private fun renderTooltip(g: GuiGraphicsCompat, lines: List<Component>, x: Int, y: Int) {
        val font = Minecraft.getInstance().font
        //? if >=1.21.11 {
        g.setComponentTooltipForNextFrame(font, lines, x, y)
        //?} else
        /*g.renderComponentTooltip(font, lines, x, y)*/
    }

    /**
     * After [render], call this before [drawChildren] to clip child widgets (sliders, buttons) to the
     * scrollable row area. Call [endChildClip] after [drawChildren]. No-op when no scrolling is active.
     */
    fun beginChildClip(g: GuiGraphicsCompat) {
        if (maxScroll <= 0) return
        g.enableScissor(clipLeft, areaTop, clipRight, areaBottom)
    }

    /** Ends the child-widget scissor started by [beginChildClip]. */
    fun endChildClip(g: GuiGraphicsCompat) {
        if (maxScroll <= 0) return
        g.disableScissor()
    }

    companion object {
        /** Pixels scrolled per mouse-wheel notch. */
        private const val SCROLL_STEP = 24
    }
}