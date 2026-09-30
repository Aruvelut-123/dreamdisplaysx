package com.dreamdisplayx.platform.client.ui.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Covers [UiText.stripEmoji], the pure-JVM half of the font-aware text helpers: emoji are dropped
 * because the Minecraft font cannot draw them (upstream issue #232 showed them as boxes).
 */
class UiTextStripEmojiTest {
    @Test
    fun `text without emoji is returned as is`() {
        val text = "A perfectly normal video title"
        assertSame(text, UiText.stripEmoji(text))
    }

    @Test
    fun `emoji between two words becomes a bullet separator`() {
        assertEquals("Never Gonna • Give You Up", UiText.stripEmoji("Never Gonna 🥰 Give You Up"))
    }

    @Test
    fun `emoji glued to both words still keeps the words apart`() {
        assertEquals("Never • Gonna", UiText.stripEmoji("Never🥰Gonna"))
    }

    @Test
    fun `leading emoji leaves no separator`() {
        assertEquals("Never Gonna", UiText.stripEmoji("🥰 Never Gonna"))
    }

    @Test
    fun `trailing emoji leaves no separator`() {
        assertEquals("Never Gonna", UiText.stripEmoji("Never Gonna 🥰"))
    }

    @Test
    fun `emoji only titles collapse to an empty string`() {
        assertEquals("", UiText.stripEmoji("🥰🥰"))
    }

    @Test
    fun `surrounding whitespace of a removed emoji is collapsed`() {
        assertEquals("Never • Gonna", UiText.stripEmoji("Never   🥰   Gonna"))
        assertEquals("Never • Gonna", UiText.stripEmoji("Never🥰   🥰Gonna"))
    }

    @Test
    fun `multi-code-point emoji sequences are removed entirely`() {
        // Zero-width-joiner family sequence; the trailing space is trimmed away.
        assertEquals("Family:", UiText.stripEmoji("Family: 👨‍👩‍👧"))
        // Keycap sequence: the digit survives and rejoins the next word with a bullet.
        assertEquals("Top 1 • today", UiText.stripEmoji("Top 1️⃣ today"))
        // Variation selectors are dropped, the base glyph is kept.
        assertEquals("❤", UiText.stripEmoji("❤️"))
    }

    @Test
    fun `digits count as word characters and still use the bullet separator`() {
        assertEquals("Track 7 • of 9", UiText.stripEmoji("Track 7🥰of 9"))
    }

    @Test
    fun `emoji next to punctuation uses a plain space`() {
        assertEquals("Hello, world!", UiText.stripEmoji("Hello,🥰world!"))
    }
}
