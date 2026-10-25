package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.ui.ClientMessage
import com.dreamdisplays.api.media.search.SortOrder
import net.minecraft.network.chat.Component

/** Sort / filter choices offered by the suggestions panel's sort dropdown. [RELEVANCE] / [POPULARITY] / [NEWEST] / [STREAMS] re-query; the rest filter locally. */
enum class SortOption(val labelKey: String, val networkSort: SortOrder) {
    RELEVANCE(ClientMessage.SORT_RELEVANCE.key, SortOrder.RELEVANCE),
    POPULARITY(ClientMessage.SORT_POPULARITY.key, SortOrder.VIEW_COUNT),
    NEWEST(ClientMessage.SORT_NEWEST.key, SortOrder.UPLOAD_DATE),
    STREAMS(ClientMessage.SORT_STREAMS.key, SortOrder.LIVE),
    UNWATCHED(ClientMessage.SORT_UNWATCHED.key, SortOrder.RELEVANCE),
    WATCHED(ClientMessage.SORT_WATCHED.key, SortOrder.RELEVANCE),
    MY_LINKS(ClientMessage.SORT_MY_LINKS.key, SortOrder.RELEVANCE);

    /** True when picking this option should re-run the current search against `YouTube`'s own sort. */
    val refetches: Boolean get() = this == RELEVANCE || this == POPULARITY || this == NEWEST || this == STREAMS

    /**
     * True when this option shows its own list rather than filtering the loaded results, so the
     * controller must swap the cards out instead of leaving the current search in place.
     */
    val isOwnList: Boolean get() = this == MY_LINKS

    fun label(): String = Component.translatable(labelKey).string
}
