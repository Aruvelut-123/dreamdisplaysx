package com.dreamdisplayx.platform.client.ui.widgets

import com.dreamdisplayx.platform.client.DreamIdentifiers
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplayx.platform.client.ui.kit.UiWidget
import com.dreamdisplayx.platform.client.ui.blitSpriteCompat
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import kotlin.math.min

/**
 * Vanilla-styled button with a centered mod icon. The icon is a per-frame lambda so stateful buttons
 * (play / pause) can swap it live.
 *
 * 1.20.1 predates `WidgetSprites` (added in 1.20.2), so this target keeps the same
 * normal/disabled/hovered trio in a small local holder and picks the entry itself.
 */
class IconButton(
    private val icon: () -> ResourceLocation,
    private val sprites: ButtonSprites = DEFAULT_SPRITES,
    private val margin: Int = 2,
    private val onPress: () -> Unit,
) : UiWidget(Component.empty()) {

    constructor(icon: String, onPress: () -> Unit) :
            this(icon = { modIcon(icon) }, onPress = onPress)

    override fun handlesWholeWidgetCursor(): Boolean = false

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isValidClickButton(button) || !clicked(mouseX, mouseY)) return false
        onPress()
        playDownSound(Minecraft.getInstance().soundManager)
        return true
    }

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (width <= 0 || height <= 0) return
        val sprite = sprites.get(active, isHoveredOrFocused)
        g.blitSpriteCompat(sprite, x, y, width, height)

        val iconSide = min(width - 2 * margin, height - 2 * margin)
        val dx = x + width / 2 - iconSide / 2
        val dy = y + height / 2 - iconSide / 2
        g.blitSpriteCompat(icon(), dx, dy, iconSide, iconSide)
    }

    companion object {
        val DEFAULT_SPRITES = ButtonSprites(
            DreamIdentifiers.vanilla("widget/button"),
            DreamIdentifiers.vanilla("widget/button_disabled"),
            DreamIdentifiers.vanilla("widget/button_highlighted"),
        )

        /** Red variant used for destructive actions (delete, report). */
        val RED_SPRITES = ButtonSprites(
            DreamIdentifiers.of(Initializer.MOD_ID, "widgets/red_button"),
            DreamIdentifiers.of(Initializer.MOD_ID, "widgets/red_button_disabled"),
            DreamIdentifiers.of(Initializer.MOD_ID, "widgets/red_button_highlighted"),
        )

        /** Resolves a mod-namespaced icon sprite by [name]. */
        fun modIcon(name: String): ResourceLocation = DreamIdentifiers.of(Initializer.MOD_ID, name)
    }
}

/**
 * The pre-1.20.2 stand-in for vanilla's `WidgetSprites`: one sprite for the normal, disabled, and
 * hovered states. `get(active, hovered)` applies vanilla's precedence (disabled wins over hovered).
 */
data class ButtonSprites(
    private val enabled: ResourceLocation,
    private val disabled: ResourceLocation,
    private val enabledFocused: ResourceLocation,
) {
    /** Picks the sprite for the current [active] / [hovered] state. */
    fun get(active: Boolean, hovered: Boolean): ResourceLocation = when {
        !active -> disabled
        hovered -> enabledFocused
        else -> enabled
    }
}