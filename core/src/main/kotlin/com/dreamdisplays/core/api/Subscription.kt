package com.dreamdisplays.core.api

import java.util.concurrent.atomic.AtomicBoolean

/** Idempotent [AutoCloseable] that runs [onClose] once. */
internal class Subscription(private val onClose: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** True once closed. */
    val isClosed: Boolean get() = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}
