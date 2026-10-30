package com.dreamdisplayx.platform.client.ui.kit

import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics
//?}
//? if <1.20 {
import com.dreamdisplayx.platform.client.ui.compat.GuiGraphics
//?}
*/
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * Base class for all Dream DisplaysX widgets.
 *
 * State is declarative: [enabledWhen] / [visibleWhen] are re-evaluated once per frame by the owning
 * [UiScreenBase] (via [syncState]) instead of call sites imperatively flipping `active` / `visible` flags.
 */
abstract class UiWidget(message: Component) : AbstractWidget(0, 0, 0, 0, message) {
    /** Re-evaluated each frame to drive [active]; widgets reject input while disabled. */
    var enabledWhen: () -> Boolean = { true }

    /** Re-evaluated each frame to drive [visible]; invisible widgets are skipped entirely. */
    var visibleWhen: () -> Boolean = { true }

    /** Moves and resizes this widget to [r]; layout code calls this once per frame. */
    fun place(r: UiRect) {
        x = r.x
        y = r.y
        width = r.w
        height = r.h
    }

    /** Pulls [enabledWhen]/[visibleWhen] into the vanilla `active`/`visible` flags. */
    fun syncState() {
        active = enabledWhen()
        visible = visibleWhen()
    }

    /**
     * Keyboard-focus helpers. `AbstractWidget.setFocused` / `isFocused` are protected on the legacy
     * versions, so widget code has to go through these instead of touching them directly.
     */
    fun requestFocus() {
        setFocused(true)
    }

    /** True while this widget owns keyboard focus. */
    fun hasFocus(): Boolean = isFocused

    /** Version-neutral render body; implement all drawing here. */
    protected abstract fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float)

    /** True for simple controls; composite widgets should request cursors only for their interactive parts. */
    protected open fun handlesWholeWidgetCursor(): Boolean = true

    //? if >=26 {
    /** Requests the cursor for this widget's hovered state. Override for controls with special cursors. */
    protected open fun requestWidgetCursor(g: GuiGraphicsExtractor) = handleCursor(g)

    final override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        draw(g, mouseX, mouseY, partialTick)
        if (handlesWholeWidgetCursor()) requestWidgetCursor(g)
    }

    /** Draws [text] centered over the widget with vanilla scrolling-on-overflow behavior. */
    protected fun drawScrollingLabel(g: GuiGraphicsCompat, text: Component, padding: Int) =
        extractScrollingStringOverContents(
            g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.TOOLTIP_AND_CURSOR),
            text, padding,
        )
    //?} else
    /*
    //? if >=1.20 {
    final override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) =
        draw(g, mouseX, mouseY, partialTick)
    //?}
    //? if >=1.19.4 <1.20 {
    final override fun renderWidget(pose: com.mojang.blaze3d.vertex.PoseStack, mouseX: Int, mouseY: Int, partialTick: Float) =
        draw(GuiGraphics(pose), mouseX, mouseY, partialTick)
    //?}
    //? if <1.19.4 {
    final override fun renderButton(pose: com.mojang.blaze3d.vertex.PoseStack, mouseX: Int, mouseY: Int, partialTick: Float) =
        draw(GuiGraphics(pose), mouseX, mouseY, partialTick)
    //?}

    //? if >=1.21.11 {
    // Draws [text] centered over the widget with vanilla scrolling-on-overflow behavior.
    protected fun drawScrollingLabel(g: GuiGraphicsCompat, text: Component, padding: Int) =
        renderScrollingStringOverContents(
            g.textRendererForWidget(this, GuiGraphics.HoveredTextEffects.TOOLTIP_AND_CURSOR),
            text,
            padding,
        )
    //?}

    //? if <1.19.4 {
    protected fun drawScrollingLabel(g: GuiGraphicsCompat, text: Component, padding: Int) =
        net.minecraft.client.gui.GuiComponent.drawCenteredString(
            g.pose(), net.minecraft.client.Minecraft.getInstance().font, text,
            x + width / 2, y + (height - 8) / 2, ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF,
        )
    //?}

    //? if >=1.19.4 <1.21.11 {
    // Draws [text] centered over the widget with vanilla scrolling-on-overflow behavior.
    protected fun drawScrollingLabel(g: GuiGraphicsCompat, text: Component, padding: Int) =
        renderScrollingString(
            //? if >=1.20 {
            g,
            //?}
            //? if <1.20 {
            g.pose(),
            //?}
            net.minecraft.client.Minecraft.getInstance().font,
            text,
            x + padding,
            y,
            x + width - padding,
            y + height,
            ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF,
        )
    //?}*/

    //? if >=1.19.3 {
    override fun updateWidgetNarration(builder: NarrationElementOutput) {}
    //?} else
    /*override fun updateNarration(builder: NarrationElementOutput) {}*/
}
