package com.dreamdisplayx.api.media.player

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Purges any cached resolution for a media URL so the next resolve hits the network fresh.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
fun interface CacheInvalidator {
    fun invalidate(url: String)
}
