package com.dreamdisplayx.platform.client.ui.widgets

import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplayx.platform.client.ui.drawText
import com.dreamdisplayx.platform.client.ui.kit.UiWidget
import com.dreamdisplayx.platform.client.ui.kit.UiTheme
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
//?}
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * A compact row of four toggle buttons for the Bilibili danmaku type filters: Scroll, Top, Bottom,
 * Color. Each button is toggled individually. [onToggle] fires with the filter index (0 = scroll,
 * 1 = top, 2 = bottom, 3 = color) and the new state.
 */
class DanmakuFilterBar(
    initialScroll: Boolean,
    initialTop: Boolean,
    initialBottom: Boolean,
    initialColor: Boolean,
    private val onToggle: (index: Int, enabled: Boolean) -> Unit,
) : UiWidget(Component.empty()) {

    /** Current filter states, index 0..3 = scroll / top / bottom / color. */
    private val states = booleanArrayOf(initialScroll, initialTop, initialBottom, initialColor)

    /** Public accessors for the current filter states. */
    val filterScroll: Boolean get() = states[0]
    val filterTop: Boolean get() = states[1]
    val filterBottom: Boolean get() = states[2]
    val filterColor: Boolean get() = states[3]

    override fun handlesWholeWidgetCursor(): Boolean = false

    /** Replaces the filter states from outside (e.g. a reset button). No [onToggle] fires. */
    fun setStates(scroll: Boolean, top: Boolean, bottom: Boolean, color: Boolean) {
        states[0] = scroll
        states[1] = top
        states[2] = bottom
        states[3] = color
    }

    private fun btnW(): Int = (width / 4).coerceAtLeast(1)

    private fun buttonAt(mx: Int): Int {
        if (width <= 0 || height <= 0) return -1
        return ((mx - x) / btnW()).coerceIn(0, 3)
    }

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (width <= 0 || height <= 0) return
        val bw = btnW()
        val font = Minecraft.getInstance().font
        val hoveredIdx = if (isHovered) buttonAt(mouseX) else -1

        for (i in 0 until 4) {
            val bx = x + i * bw
            val active = states[i]
            val hovered = i == hoveredIdx

            val bgColor = when {
                active && hovered -> 0xFFC8E6FF.toInt()
                active -> 0xFF3A5A8A.toInt()
                hovered -> UiTheme.HOVER_FILL
                else -> 0x20000000
            }
            g.fill(bx, y, bx + bw, y + height, bgColor)
            if (i > 0) g.fill(bx, y, bx + 1, y + height, 0x40FFFFFF)

            val textColor = if (active) 0xFFFFFFFF.toInt() else 0xFF808080.toInt()
            g.drawText(font, labelFor(i), bx + bw / 2 - font.width(labelFor(i)) / 2, y + 4, textColor, false)
        }

        //? if >=1.21.11 {
        if (isHovered) {
            g.requestCursor(CursorTypes.POINTING_HAND)
        }
        //?}
    }

    private fun labelFor(i: Int): Component = when (i) {
        0 -> Component.translatable("dreamdisplayx.button.danmaku.filter.scroll.short")
        1 -> Component.translatable("dreamdisplayx.button.danmaku.filter.top.short")
        2 -> Component.translatable("dreamdisplayx.button.danmaku.filter.bottom.short")
        else -> Component.translatable("dreamdisplayx.button.danmaku.filter.color.short")
    }

    //? if >=1.21.11 {
    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        toggleAt(event.x().toInt())
        playDownSound(Minecraft.getInstance().soundManager)
    }
    //?} else
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isValidClickButton(button) || !clicked(mouseX, mouseY)) return false
        toggleAt(mouseX.toInt())
        playDownSound(Minecraft.getInstance().soundManager)
        return true
    }*/

    private fun toggleAt(mx: Int) {
        val idx = buttonAt(mx)
        if (idx !in 0..3) return
        states[idx] = !states[idx]
        // Keep at least one filter enabled so the danmaku screen never goes completely empty.
        if (!states.any { it }) states[idx] = true
        onToggle(idx, states[idx])
    }

    override fun createNarrationMessage(): MutableComponent =
        Component.translatable("dreamdisplayx.button.danmaku.filter")

    /** Tooltip for the current filter states (used by the settings row tooltip). */
    fun stateTooltip(): List<Component> = listOf(
        Component.translatable("dreamdisplayx.button.danmaku.filter.tooltip.1"),
        Component.translatable(
            "dreamdisplayx.button.danmaku.filter.tooltip.2",
            Component.translatable(if (states[0]) "dreamdisplayx.button.enabled" else "dreamdisplayx.button.disabled"),
            Component.translatable(if (states[1]) "dreamdisplayx.button.enabled" else "dreamdisplayx.button.disabled"),
            Component.translatable(if (states[2]) "dreamdisplayx.button.enabled" else "dreamdisplayx.button.disabled"),
            Component.translatable(if (states[3]) "dreamdisplayx.button.enabled" else "dreamdisplayx.button.disabled"),
        ),
    )
}