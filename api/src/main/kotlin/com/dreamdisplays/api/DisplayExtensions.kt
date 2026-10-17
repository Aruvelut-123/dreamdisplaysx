package com.dreamdisplays.api

/**
 * Describes how to react to the display's life in one block. Ends by itself when the display is removed.
 *
 * Example:
 * ```
 * display.lifecycle {
 *     onPlaying { println("Playing!") }
 *     onFailed  { display.retry() }
 *     onRemoved { println("Gone!") }
 * }
 * ```
 *
 * @since 1.10.x
 */
@Unstable
inline fun Display.lifecycle(crossinline block: DisplayLifecycle.() -> Unit): AutoCloseable =
    watch { it.block() }
