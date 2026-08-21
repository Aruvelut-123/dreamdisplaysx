package com.dreamdisplayx.platform.client.ui

import com.dreamdisplayx.api.media.audio.model.AcousticQuality
import com.dreamdisplayx.platform.client.Config
import com.dreamdisplayx.platform.client.ConfigEntry
import com.dreamdisplayx.platform.client.ConfigEntryType
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.dreamdisplayx.platform.client.ui.kit.UiRect
import com.dreamdisplayx.platform.client.ui.kit.UiScreenBase
import com.dreamdisplayx.platform.client.ui.kit.UiTheme
import com.dreamdisplayx.platform.client.ui.kit.drawPanel
import com.dreamdisplayx.platform.client.ui.widgets.ModeSlider
import com.dreamdisplayx.platform.client.ui.widgets.ToggleSwitch
import com.dreamdisplayx.platform.client.ui.widgets.ValueSlider
import com.dreamdisplayx.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/**
 * A Configured-like config screen generated from the [Config.configEntries] declaration. Each entry
 * renders the control matching its [ConfigEntryType] (toggle / slider / mode selector) together with
 * its comment, so new settings appear automatically without hand-writing a screen per field.
 *
 * This is used as the Fabric ModMenu "Configure" screen (and can be opened directly from other code).
 */
class ClientConfigScreen(private val parent: net.minecraft.client.gui.screens.Screen?) :
    UiScreenBase(Component.literal("Dream DisplaysX Config")) {

    private data class Row<T>(
        val entry: ConfigEntry<T>,
        val widget: com.dreamdisplayx.platform.client.ui.kit.UiWidget,
        val height: Int,
    )

    private val rows = ArrayList<Row<*>>()

    private fun config(): Config = ClientStateManager.config

    @Suppress("UNCHECKED_CAST")
    private fun <T> buildRow(entry: ConfigEntry<T>): Row<T> {
        val widget: com.dreamdisplayx.platform.client.ui.kit.UiWidget
        val height: Int
        when (entry.type) {
            ConfigEntryType.BOOLEAN -> {
                val initial = entry.get() as Boolean
                widget = ToggleSwitch(
                    initial = initial,
                    label = { v -> Component.literal("${entry.label}: ${onOff(v)}") },
                    onApply = { v -> (entry.apply as (Boolean) -> Unit)(v) },
                )
                height = 24
            }
            ConfigEntryType.ENUM -> {
                val values = entry.values as List<Any>
                widget = ModeSlider<Any>(
                    modes = values,
                    initial = entry.get() as Any,
                    current = { entry.get() as Any },
                    enabledFor = { true },
                    label = { v -> Component.literal("${entry.label}: $v") },
                    onApply = { v -> (entry.apply as (Any) -> Unit)(v) },
                )
                height = 24
            }
            ConfigEntryType.INT -> {
                val initial = (entry.get() as Int).toDouble()
                widget = ValueSlider(
                    initial = initial,
                    label = { v -> Component.literal("${entry.label}: ${v.roundToInt()}") },
                    live = false,
                ) { v -> (entry.apply as (Int) -> Unit)(v.roundToInt()) }
                height = 24
            }
            ConfigEntryType.DOUBLE -> {
                val initial = entry.get() as Double
                widget = ValueSlider(
                    initial = initial,
                    label = { v -> Component.literal("${entry.label}: ${"%.2f".format(v)}") },
                    live = false,
                ) { v -> (entry.apply as (Double) -> Unit)(v) }
                height = 24
            }
            ConfigEntryType.STRING -> {
                // Strings are displayed read-only with their current value; editing is out of scope.
                widget = ToggleSwitch(
                    initial = false,
                    label = { Component.literal("${entry.label}: ${entry.get()}") },
                    onApply = {},
                )
                height = 24
            }
        }
        return Row(entry, widget, height)
    }

    override fun init() {
        super.init()
        rows.clear()
        for (entry in config().configEntries()) {
            rows.add(buildRow(entry))
        }
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawScreenBackground(g)

        val pad = UiTheme.SCREEN_PADDING
        val panel = UiRect(pad, pad, width - pad * 2, height - pad * 2)
        g.drawPanel(font, panel, "Dream DisplaysX Config")

        val inner = UiRect(panel.x + 12, panel.y + 26, panel.w - 24, panel.h - 38)
        var y = inner.y
        for (row in rows) {
            row.widget.place(UiRect(inner.x, y, inner.w, row.height - 6))
            y += row.height
            // Comment line under the control, dimmed, like a Configured tooltip.
            val fontH = font.lineHeight
            g.drawText(
                font,
                row.entry.comment,
                inner.x + 2,
                y,
                if (y + fontH <= inner.y + inner.h) 0xA0A0A0 else 0xFF404040.toInt(),
                false,
            )
            y += fontH + 2
        }

        drawChildren(g, mouseX, mouseY, partialTick)
    }

    override fun onClose() {
        MinecraftScreenUtil.setScreen(Minecraft.getInstance(), parent)
    }

    private fun onOff(v: Boolean): String = if (v) "ON" else "OFF"
}
