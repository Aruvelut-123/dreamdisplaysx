package com.dreamdisplayx.core.addon

import com.dreamdisplays.core.addon.AddonBinder
import com.dreamdisplayx.api.addon.ForkAddonContext

/**
 * Runs an upstream `AddonBinder` against a fork-managed addon.
 *
 * upstream's binder family takes an `AddonContext`; the fork manager hands out a [ForkAddonContext]. Because
 * [ForkAddonHandle] already implements everything `own` needs, wrapping it in [UpstreamContextView] is enough — and
 * that view shares the handle's resource list, so a resource owned through either interface is closed exactly once.
 *
 * This is what lets one manager host both annotation families without duplicating subscription logic.
 *
 * @since 1.10.x
 */
class UpstreamBinderAdapter(private val delegate: AddonBinder) : ForkAddonBinder {
    override fun bind(addon: Any, context: ForkAddonContext) {
        val handle = context as? ForkAddonHandle
            ?: error("UpstreamBinderAdapter needs a ForkAddonHandle, got ${context.javaClass.name}.")
        delegate.bind(addon, UpstreamContextView(handle))
    }
}