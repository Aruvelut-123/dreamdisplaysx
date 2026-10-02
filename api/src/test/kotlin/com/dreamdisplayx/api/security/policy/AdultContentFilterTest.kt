package com.dreamdisplayx.api.security.policy

import com.dreamdisplayx.api.media.search.model.MediaSearchResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guard rails for [AdultContentFilter]: unambiguous adult titles must be flagged, while innocent
 * near-matches (word-boundary siblings, gaming / tech / slang uses) must survive.
 */
class AdultContentFilterTest {

    @Test
    fun `chinese adult term in title is flagged`() {
        assertTrue(AdultContentFilter.isExplicit("无码 高清 在线观看"))
        assertTrue(AdultContentFilter.isExplicit("老板的秘密 色情 小剧场"))
    }

    @Test
    fun `chinese adult term in uploader is flagged`() {
        assertTrue(AdultContentFilter.isExplicit(null, "福利姬搬运工"))
    }

    @Test
    fun `latin adult word is flagged case-insensitively`() {
        assertTrue(AdultContentFilter.isExplicit("Top 10 PORN sites"))
        assertTrue(AdultContentFilter.isExplicit("HENTAI compilation"))
        assertTrue(AdultContentFilter.isExplicit("naked dress challenge"))
    }

    @Test
    fun `word-boundary siblings do not over-match`() {
        // "av" must not match "avatar / available / Ave Maria".
        assertFalse(AdultContentFilter.isExplicit("avatar movie review"))
        assertFalse(AdultContentFilter.isExplicit("available now on steam"))
        // "sex" must not match "sexy outfit" (fashion) or "sextant".
        assertFalse(AdultContentFilter.isExplicit("sexy cosplay outfit"))
    }

    @Test
    fun `ambiguous ordinary words survive`() {
        assertFalse(AdultContentFilter.isExplicit("成人教育 普通话 考试"))
        assertFalse(AdultContentFilter.isExplicit("welcome to the hotel"))
        assertFalse(AdultContentFilter.isExplicit("minecraft 生存 教程 2024"))
        assertFalse(AdultContentFilter.isExplicit("福利 游戏 领取"))
    }

    @Test
    fun `blank and null fields are safe`() {
        assertFalse(AdultContentFilter.isExplicit(""))
        assertFalse(AdultContentFilter.isExplicit("   "))
        assertFalse(
            AdultContentFilter.isExplicitResult(
                MediaSearchResult(id = "a", title = "", uploader = null, durationSec = null, viewCount = null),
            ),
        )
        assertFalse(AdultContentFilter.isExplicit(null, null))
    }

    @Test
    fun `result combines title and uploader`() {
        val hidden = MediaSearchResult(
            id = "bv1",
            title = "深夜直播回放",
            uploader = "小XX 无码",
            durationSec = null,
            viewCount = null,
        )
        assertTrue(AdultContentFilter.isExplicitResult(hidden))
    }
}