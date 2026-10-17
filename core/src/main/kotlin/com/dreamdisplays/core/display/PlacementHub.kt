package com.dreamdisplays.core.display

import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.placement.DisplayPlacement
import com.dreamdisplays.api.display.placement.DisplayPlacementProvider
import com.dreamdisplays.api.display.placement.DisplayPlacements
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the registered [DisplayPlacementProvider]s. The client asks it for every display on every frame, so the common
 * case (nobody registered) costs one volatile read.
 */
object PlacementHub : DisplayPlacements {
    private val providers = CopyOnWriteArrayList<DisplayPlacementProvider>()

    @Volatile
    var isEmpty: Boolean = true; private set

    override fun register(provider: DisplayPlacementProvider): AutoCloseable {
        providers += provider
        isEmpty = false
        return AutoCloseable {
            providers -= provider
            isEmpty = providers.isEmpty()
        }
    }

    /**
     * The placement of [id] according to the first provider that knows it, or `null`.
     *
     * A throwing provider is skipped.
     */
    fun placementOf(id: DisplayId): DisplayPlacement? {
        if (isEmpty) return null
        for (provider in providers) {
            val placement = runCatching { provider.placementOf(id) }.getOrNull()
            if (placement != null) return placement
        }
        return null
    }
}
