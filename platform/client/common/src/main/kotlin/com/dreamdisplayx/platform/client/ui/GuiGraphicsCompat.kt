package com.dreamdisplayx.platform.client.ui

import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*import net.minecraft.client.gui.GuiGraphics*/

//? if <1.21.11 {
import com.dreamdisplayx.platform.client.DreamIdentifiers
import net.minecraft.resources.ResourceLocation
//?}

/**
 * Version-neutral alias for the per-frame GUI draw target. Minecraft 26+ draws into a [GuiGraphicsExtractor];
 * older versions use the vanilla `GuiGraphics` type directly.
 */
//? if >=26 {
typealias GuiGraphicsCompat = GuiGraphicsExtractor
//?} else
/*typealias GuiGraphicsCompat = GuiGraphics*/

/**
 * Draws a single line of [text] with [font] at ([x], [y]) in ARGB [color], optionally with a drop
 * [shadow]. Maps to `text()` on 26+ and `drawString()` on pre-26. The one call that was responsible
 * for the bulk of the duplicated render code.
 */
//? if >=26 {
fun GuiGraphicsCompat.drawText(font: Font, text: String, x: Int, y: Int, color: Int, shadow: Boolean) {
    this.text(font, text, x, y, color, shadow)
}
//?} else
/*fun GuiGraphicsCompat.drawText(font: Font, text: String, x: Int, y: Int, color: Int, shadow: Boolean) {
    this.drawString(font, text, x, y, color, shadow)
}*/

/** [Component] overload of [drawText]: `text()` on 26+, `drawString()` on pre-26. */
//? if >=26 {
fun GuiGraphicsCompat.drawText(font: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean) {
    this.text(font, text, x, y, color, shadow)
}
//?} else
/*fun GuiGraphicsCompat.drawText(font: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean) {
    this.drawString(font, text, x, y, color, shadow)
}*/

//? if <1.21.11 {
fun GuiGraphicsCompat.enableScissorPoseAware(x1: Int, y1: Int, x2: Int, y2: Int) {
    val m = pose().last().pose()
    val p1 = m.transformPosition(org.joml.Vector3f(x1.toFloat(), y1.toFloat(), 0f))
    val p2 = m.transformPosition(org.joml.Vector3f(x2.toFloat(), y2.toFloat(), 0f))
    enableScissor(p1.x.toInt(), p1.y.toInt(), p2.x.toInt(), p2.y.toInt())
}

/**
 * Draws a GUI sprite scaled into a `w` x `h` rectangle.
 *
 * Pre-1.20.2 Minecraft has no GUI sprite system at all: `GuiSpriteManager` and `blitSprite` only
 * arrive in 1.20.2, and the 1.20.1 client jar contains zero `textures/gui/sprites/` entries. A
 * `widgets/panel`-style id therefore has to be resolved back to the plain texture that holds the
 * sprite — `assets/<namespace>/textures/gui/sprites/widgets/panel.png` — and drawn with the legacy
 * `blit`. The previous implementation passed the sprite id straight to `blit`, which resolves it as
 * `textures/<path>.png`; that asked for `textures/dreamdisplayx/widgets/panel.png`, missed, and
 * painted the purple/black missing-texture checkerboard over every panel, icon and slider.
 *
 * [textureW] / [textureH] are the source PNG dimensions for sprites this mod does not own; its own
 * sprites are resolved from [SPRITE_SOURCES], because the real sizes matter — the 64x64 panel and the
 * 200x20 red buttons were previously sampled as if they were 16x16, which stretched the art and cut
 * off its authored nine-slice corners, while the 24x24 icons sampled only their top-left corner.
 */
fun GuiGraphicsCompat.blitSpriteCompat(
    sprite: ResourceLocation,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    textureW: Int = 16,
    textureH: Int = 16,
) {
    // Vanilla widget sprites are 1.20.2+ ids with no pre-1.20.2 counterpart at all; draw those
    // procedurally instead of asking the texture manager for a file that cannot exist.
    if (sprite.namespace == "minecraft" && sprite.path.startsWith("widget/")) {
        drawLegacyWidget(sprite.path, x, y, w, h)
        return
    }
    if (sprite.path.startsWith("textures/")) {
        blitScaled(sprite, x, y, w, h, textureW, textureH)
        return
    }
    val source = SPRITE_SOURCES[sprite.path]
    if (source == null) {
        // Unknown sprite: flat icon art drawn at its authored 24x24 size.
        blitScaled(spriteTexture(sprite), x, y, w, h, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE)
        return
    }
    blitNineSlice(spriteTexture(sprite), x, y, w, h, source)
}

/**
 * Resolves a GUI sprite id to the texture that actually holds it on pre-1.20.2 clients.
 *
 * Sprites live under `textures/gui/sprites/`, so `dreamdisplayx:widgets/panel` becomes
 * `dreamdisplayx:textures/gui/sprites/widgets/panel.png`. Ids that already spell out a texture path
 * are returned unchanged, which keeps vanilla fallbacks (`minecraft:widgets` style) working.
 */
internal fun spriteTexture(sprite: ResourceLocation): ResourceLocation {
    val path = sprite.path
    if (path.startsWith("textures/")) return sprite
    return DreamIdentifiers.of(sprite.namespace, "textures/gui/sprites/$path.png")
}

/**
 * Source geometry of one GUI sprite on pre-1.20.2 clients: the PNG it lives in, its pixel size and
 * the nine-slice border declared by its `.mcmeta`. Mirrors the atlas data 1.20.2+ would build.
 */
private class SpriteSource(val width: Int, val height: Int, val border: Int)

/**
 * Layout of this mod's own GUI sprites, which 1.20.1 has to read as plain textures.
 *
 * Must stay in sync with the PNGs under `assets/dreamdisplayx/textures/gui/sprites/` and their
 * `.mcmeta` nine-slice borders; the sizes are the authors' originals, not an atlas guess.
 */
private val SPRITE_SOURCES: Map<String, SpriteSource> = mapOf(
    "widgets/panel" to SpriteSource(64, 64, 3),
    "widgets/dropdown" to SpriteSource(16, 16, 3),
    "widgets/red_button" to SpriteSource(200, 20, 3),
    "widgets/red_button_disabled" to SpriteSource(200, 20, 1),
    "widgets/red_button_highlighted" to SpriteSource(200, 20, 3),
)

/** Icon sprites are flat 24x24 art with no nine-slice border. */
private const val ICON_SOURCE_SIZE = 24

/**
 * Stitches a nine-slice texture into a `w` x `h` rectangle: corners keep their pixel size, edges and
 * the centre stretch. Falls back to a single stretched blit when the target is smaller than the
 * borders, where a nine-slice would overlap itself.
 */
private fun GuiGraphicsCompat.blitNineSlice(
    texture: ResourceLocation,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    source: SpriteSource,
) {
    val b = source.border
    val tw = source.width
    val th = source.height
    if (b <= 0 || w < b * 2 || h < b * 2) {
        blitScaled(texture, x, y, w, h, tw, th)
        return
    }
    val midW = w - b * 2
    val midH = h - b * 2
    val srcMidW = tw - b * 2
    val srcMidH = th - b * 2
    // Corners.
    blitScaled(texture, x, y, b, b, tw, th, 0f, 0f, b, b)
    blitScaled(texture, x + w - b, y, b, b, tw, th, (tw - b).toFloat(), 0f, b, b)
    blitScaled(texture, x, y + h - b, b, b, tw, th, 0f, (th - b).toFloat(), b, b)
    blitScaled(texture, x + w - b, y + h - b, b, b, tw, th, (tw - b).toFloat(), (th - b).toFloat(), b, b)
    // Edges.
    blitScaled(texture, x + b, y, midW, b, tw, th, b.toFloat(), 0f, srcMidW, b)
    blitScaled(texture, x + b, y + h - b, midW, b, tw, th, b.toFloat(), (th - b).toFloat(), srcMidW, b)
    blitScaled(texture, x, y + b, b, midH, tw, th, 0f, b.toFloat(), b, srcMidH)
    blitScaled(texture, x + w - b, y + b, b, midH, tw, th, (tw - b).toFloat(), b.toFloat(), b, srcMidH)
    // Centre.
    if (midW > 0 && midH > 0) {
        blitScaled(texture, x + b, y + b, midW, midH, tw, th, b.toFloat(), b.toFloat(), srcMidW, srcMidH)
    }
}

/**
 * Draws (part of) [texture] into a `dw` x `dh` destination rect on pre-1.20.2 clients.
 *
 * The plain 9-argument `blit(texture, x, y, u, v, w, h, texW, texH)` overload cannot scale: it
 * forwards the *destination* size as the sampled `uWidth`/`vHeight`, so drawing a 64x64 panel into
 * a 400x200 rect asks for UV `0..6.25`. Vanilla samplers use `GL_REPEAT`, so the sprite tiles across
 * the whole panel and its nine-slice borders repeat into a grid. Only the 11-argument overload takes
 * the sampled size separately from the destination size, and that is what every blit here uses.
 *
 * [sw] / [sh] default to the full source image; the nine-slice passes its slice sizes instead.
 */
private fun GuiGraphicsCompat.blitScaled(
    texture: ResourceLocation,
    dx: Int,
    dy: Int,
    dw: Int,
    dh: Int,
    texW: Int,
    texH: Int,
    u: Float = 0f,
    v: Float = 0f,
    sw: Int = texW,
    sh: Int = texH,
) {
    if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0 || texW <= 0 || texH <= 0) return
    blit(texture, dx, dy, dw, dh, u, v, sw, sh, texW, texH)
}

/**
 * Draws a flat-colour stand-in for a 1.20.2+ vanilla widget sprite on 1.20.1.
 *
 * `minecraft:widget/slider`, `widget/button` and friends live in a sprite atlas that 1.20.1 simply
 * does not have (the client jar contains zero `textures/gui/sprites/` entries), so asking the
 * texture manager for them yields the missing-texture checkerboard. Vanilla 1.20.1 assets keep those
 * controls inside `textures/gui/widgets.png` / `slider.png`, but those sheets have their own
 * hand-tuned UVs; rather than guessing at them this draws a neutral dark track with a light handle,
 * which matches this mod's own theme and never shows a missing texture.
 */
private fun GuiGraphicsCompat.drawLegacyWidget(path: String, x: Int, y: Int, w: Int, h: Int) {
    if (w <= 0 || h <= 0) return
    val track = 0x40FFFFFF
    val handle = 0xFFD8DCE6.toInt()
    if (path.contains("slider") || path.contains("button")) {
        val fillColor = if (path.contains("highlight") || path.contains("hover") || path.contains("focused")) {
            0x60FFFFFF
        } else {
            0x30FFFFFF
        }
        fill(x, y, x + w, y + h, fillColor)
        outline(x, y, w, h, track)
        if (path.contains("handle")) {
            // Handles are drawn 8px wide by the call sites; keep them crisp and high-contrast.
            fill(x, y, x + w, y + h, handle)
            outline(x, y, w, h, 0xFF2A2E38.toInt())
        }
        return
    }
    fill(x, y, x + w, y + h, 0x30FFFFFF)
    outline(x, y, w, h, track)
}

/** Draws a 1px border just inside the `w` x `h` rect at ([x], [y]). */
private fun GuiGraphicsCompat.outline(x: Int, y: Int, w: Int, h: Int, color: Int) {
    fill(x, y, x + w, y + 1, color)
    fill(x, y + h - 1, x + w, y + h, color)
    fill(x, y, x + 1, y + h, color)
    fill(x + w - 1, y, x + w, y + h, color)
}
//?}
