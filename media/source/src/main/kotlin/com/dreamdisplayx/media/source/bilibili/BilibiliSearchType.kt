package com.dreamdisplayx.media.source.bilibili

/**
 * Media-type filter for the Bilibili search panel. Matches the [BilibiliSearchItem.mediaType] field
 * returned by the search API ("video", "media_bangumi", "pgc").
 */
enum class BilibiliSearchType(val apiName: String, val labelKey: String) {
    ALL("all", "dreamdisplayx.sort.bilibili.all"),
    VIDEO("video", "dreamdisplayx.sort.bilibili.video"),
    BANGUMI("media_bangumi", "dreamdisplayx.sort.bilibili.bangumi"),
    MOVIE("pgc", "dreamdisplayx.sort.bilibili.movie");

    /** True when this filter would include [item]. */
    fun matches(item: BilibiliSearchItem): Boolean = apiName == "all" || item.mediaType == apiName
}
