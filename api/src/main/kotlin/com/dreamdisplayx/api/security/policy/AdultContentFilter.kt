package com.dreamdisplayx.api.security.policy

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.search.model.MediaSearchResult
import java.util.Locale
import java.util.regex.Pattern

/**
 * Keyword-based filter that flags search results whose title / uploader clearly point at adult
 * content, so the search box never surfaces them.
 *
 * Matching is deliberately conservative: only unambiguous adult terms are treated as signals, and
 * Latin keywords are matched at word boundaries (so `av` never trips on "avatar", `sex` never trips
 * on "sexy clothes"). Short or ambiguous terms (e.g. "telegram", "福利" alone) are intentionally not
 * listed to avoid over-filtering legitimate results.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
object AdultContentFilter {

    /** Chinese adult terms; CJK has no word boundaries, so each is a substring match on the raw text. */
    private val CJK_TERMS: List<String> = listOf(
        "色情", "情色", "无码", "裸体", "裸照", "露点", "艳舞", "脱衣舞",
        "援交", "约炮", "卖淫", "嫖娼", "口交", "肛交", "自慰", "手淫", "做爱",
        "性交", "性爱", "淫乱", "淫荡", "黄片", "三级片", "毛片", "福利姬", "肉便器",
        "18禁", "成人电影", "成人视频", "成人影片",
    )

    /** Latin adult terms, matched at word boundaries so short tokens don't over-match. */
    private val LATIN_PATTERNS: List<Pattern> = listOf(
        "porn", "pornhub", "hentai", "naked", "nude", "erotic", "sex", "sextape",
        "blowjob", "handjob", "masturbat", "gangbang", "fuck", "fucking", "onlyfans",
        "av", "xxx", "r18", "r-18", "18\\+",
    ).map { Pattern.compile("\\b$it\\b", Pattern.CASE_INSENSITIVE) }

    /**
     * Returns true when [text] clearly advertises adult content. Empty / null-ish inputs never match.
     */
    fun isExplicit(text: String): Boolean {
        if (text.isBlank()) return false
        val haystack = text.lowercase(Locale.ROOT)
        if (CJK_TERMS.any { haystack.contains(it) }) return true
        return LATIN_PATTERNS.any { it.matcher(haystack).find() }
    }

    /**
     * Returns true when any of [fields] clearly advertises adult content; used to filter a search
     * result across its title / uploader without building a full [MediaSearchResult].
     */
    fun isExplicit(vararg fields: String?): Boolean = fields.any { it != null && isExplicit(it) }

    /** Returns true when the result's title or uploader clearly advertises adult content. */
    fun isExplicitResult(result: MediaSearchResult): Boolean =
        isExplicit(result.title, result.uploader)
}
