package com.dreamdisplayx.platform.client.ui.widgets

import com.dreamdisplayx.api.media.search.model.SortOrder
import net.minecraft.network.chat.Component

/** Sort / filter choices offered by the suggestions panel's sort dropdown. [RELEVANCE] / [POPULARITY] / [NEWEST] / [STREAMS] re-query; the rest filter locally. */
enum class SortOption(val labelKey: String, val networkSort: SortOrder) {
    RELEVANCE("dreamdisplayx.sort.relevance", SortOrder.RELEVANCE),
    POPULARITY("dreamdisplayx.sort.popularity", SortOrder.VIEW_COUNT),
    NEWEST("dreamdisplayx.sort.newest", SortOrder.UPLOAD_DATE),
    STREAMS("dreamdisplayx.sort.streams", SortOrder.LIVE),
    UNWATCHED("dreamdisplayx.sort.unwatched", SortOrder.RELEVANCE),
    WATCHED("dreamdisplayx.sort.watched", SortOrder.RELEVANCE),
    MY_LINKS("dreamdisplayx.sort.my_links", SortOrder.RELEVANCE);

    /** True when picking this option should re-run the current search against `YouTube`'s own sort. */
    val refetches: Boolean get() = this == RELEVANCE || this == POPULARITY || this == NEWEST || this == STREAMS

    /**
     * True when this option shows its own list rather than filtering the loaded results, so the
     * controller must swap the cards out instead of leaving the current search in place.
     */
    val isOwnList: Boolean get() = this == MY_LINKS

    fun label(): String = Component.translatable(labelKey).string
}
