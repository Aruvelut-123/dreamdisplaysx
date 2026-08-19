package com.dreamdisplayx.api.platform.capability

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Cancellable handle returned by scheduled platform tasks.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
fun interface TaskHandle {
    /** Cancels future executions when the platform scheduler supports cancellation. */
    fun cancel()
}
