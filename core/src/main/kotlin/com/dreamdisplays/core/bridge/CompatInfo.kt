package com.dreamdisplays.core.bridge

import com.dreamdisplays.api.display.model.DisplayInfo
import com.dreamdisplays.api.display.model.property.DisplayId as CompatDisplayId
import com.dreamdisplayx.api.display.model.Display as XDisplay

/**
 * Projects a fork display snapshot onto the upstream `DisplayInfo` shape.
 *
 * Everything except [DisplayInfo.customData] maps one-to-one; the fork stores no addon payload per display, so
 * that field is always empty rather than `null`.
 *
 * @since 1.10.x
 */
internal fun XDisplay.toCompatInfo(): DisplayInfo = DisplayInfo(
    id = id.toCompat(),
    bounds = bounds.toCompat(),
    settings = settings.toCompat(),
    url = url,
    state = state.toCompat(),
    mode = mode.toCompat(),
    watchParty = watchParty?.toCompat(),
    customData = emptyMap(),
)

/** A fresh compatible id for the fork id of this display. */
internal val XDisplay.compatId: CompatDisplayId get() = id.toCompat()