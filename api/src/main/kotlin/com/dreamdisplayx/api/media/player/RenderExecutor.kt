package com.dreamdisplayx.api.media.player

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Runs a task on the platform's render thread (e.g. `Minecraft.getInstance().execute`).
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
fun interface RenderExecutor {
    fun execute(task: () -> Unit)
}
