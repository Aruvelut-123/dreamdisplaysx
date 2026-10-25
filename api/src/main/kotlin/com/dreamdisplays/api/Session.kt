package com.dreamdisplays.api

/**
 * Owner of subscriptions. Everything started through a session ends when the session is closed:
 * ```
 * DreamDisplays.session("my-addon") {
 *     val screen = display(id) ?: return@session
 *     screen.lifecycle { onFailed { screen.retry() } }
 * }   // Closed here, every subscription above is gone
 * ```
 *
 * @since 1.10.x
 */
@Unstable
interface Session : AutoCloseable {
    /** Name shown in diagnostics. */
    val name: String

    /** True after [close]. */
    val isClosed: Boolean

    /** Like [DreamDisplays.display], but subscriptions made on the result belong to this session. */
    fun display(id: com.dreamdisplays.api.display.model.property.DisplayId): Display?

    /** Like [DreamDisplays.displays], but [Displays.each] subscriptions belong to this session. */
    val displays: Displays

    /**
     * Makes [resource] close together with this session; closed in reverse order.
     *
     * @return [resource].
     */
    fun <T : AutoCloseable> own(resource: T): T

    /**
     * Ends every subscription of this session.
     *
     * Safe to call twice.
     */
    override fun close()
}
