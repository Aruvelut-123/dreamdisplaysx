package com.dreamdisplayx.api.display.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.display.model.settings.DisplaySettings
import com.dreamdisplayx.api.playback.model.DisplayAccess

/**
 * Display mutation port.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
interface DisplayMutationPort {
    /** Updates the display settings for [id]. */
    fun updateSettings(id: DisplayId, settings: DisplaySettings)

    /** Sets the URL for [id]. */
    fun setUrl(id: DisplayId, url: String?, lang: String? = null)

    /** Sets who may use [id]. */
    fun setAccess(id: DisplayId, access: DisplayAccess)

    /** Deletes [id]. */
    fun delete(id: DisplayId)

    /** Reports [id]. */
    fun report(id: DisplayId)
}
