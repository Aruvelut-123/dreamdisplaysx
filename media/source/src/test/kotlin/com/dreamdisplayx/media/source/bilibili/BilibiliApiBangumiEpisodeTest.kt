package com.dreamdisplayx.media.source.bilibili

import com.dreamdisplayx.util.optLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [BilibiliApi.selectBangumiEpisode], the episode picker behind `bangumi/play/ep<id>` links.
 *
 * Regression guard for the reported "a pasted link resolved to a different video": a not-found
 * episode id used to fall back to `episodes.first()`, so a link to episode N played the season's
 * first episode instead.
 */
class BilibiliApiBangumiEpisodeTest {

    private fun ep(id: Long): JsonObject = buildJsonObject { put("ep_id", id) }

    @Test
    fun `explicit episode id picks the matching main-run episode`() {
        val episodes = listOf(ep(11L), ep(22L), ep(33L))
        assertEquals(22L, BilibiliApi.selectBangumiEpisode(episodes, emptyList(), 22L)?.optLong("ep_id"))
    }

    @Test
    fun `explicit episode id also finds section (special) episodes`() {
        val sections = listOf(ep(99L))
        assertEquals(99L, BilibiliApi.selectBangumiEpisode(listOf(ep(11L)), sections, 99L)?.optLong("ep_id"))
    }

    @Test
    fun `missing episode id must not fall back to the first episode`() {
        // The old behaviour returned episodes.first() here — the wrong video for the requested link.
        assertNull(BilibiliApi.selectBangumiEpisode(listOf(ep(11L), ep(22L)), emptyList(), 42L))
    }

    @Test
    fun `season-only request starts at the first episode`() {
        assertEquals(11L, BilibiliApi.selectBangumiEpisode(listOf(ep(11L), ep(22L)), emptyList(), null)?.optLong("ep_id"))
    }

    @Test
    fun `season-only request with no episodes yields null`() {
        assertNull(BilibiliApi.selectBangumiEpisode(emptyList(), emptyList(), null))
    }
}
