package com.dreamdisplays.core.addon

import com.dreamdisplays.api.addon.Addon
import com.dreamdisplays.api.addon.AddonContext
import com.dreamdisplays.api.addon.AddonSide
import com.dreamdisplays.api.addon.DreamDisplaysAddon
import org.slf4j.LoggerFactory

/** Connects an addon's annotated methods to the events of one side (client or server). */
fun interface AddonBinder {
    /** Subscribes the annotated methods of [addon]; everything subscribed must be handed to [context].own. */
    fun bind(addon: Any, context: AddonContext)
}

/**
 * Hosts addons on one [side]. Addons can be registered at any time; they are enabled while the host is running and
 * disabled (with every subscription closed) when it stops. A failing addon is disabled on its own and never takes the
 * others, or the mod, down.
 */
class AddonManager(
    private val side: AddonSide,
    private val binders: List<AddonBinder>,
) {
    private val logger = LoggerFactory.getLogger(AddonManager::class.java)
    private val addons = LinkedHashMap<String, Entry>()
    private var running = false

    /** IDs of the addons that are enabled right now. */
    val enabledIds: Set<String>
        @Synchronized get() = addons.values.filter { it.context != null }.map { it.id }.toSet()

    /** Registers [addon] (an instance of a class annotated with [Addon]) and enables it if the host is running. */
    @Synchronized
    fun register(addon: Any) {
        val meta = addon.javaClass.getAnnotation(Addon::class.java)
            ?: throw IllegalArgumentException("${addon.javaClass.name} must be annotated with @Addon to be registered.")
        require(meta.id.isNotBlank()) { "Addon id must not be blank (${addon.javaClass.name})." }
        require(meta.id !in addons) { "Addon '${meta.id}' is already registered." }
        val entry = Entry(meta.id, meta.side, addon)
        addons[meta.id] = entry
        if (running) enable(entry)
    }

    /** Starts the host: enables every registered addon that runs on this side. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        addons.values.forEach(::enable)
    }

    /**
     * Stops the host: disables every enabled addon in reverse order.
     *
     * Registrations stay for a later [start].
     */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        addons.values.toList().asReversed().forEach(::disable)
    }

    private fun enable(entry: Entry) {
        if (entry.context != null) return
        if (entry.side != AddonSide.BOTH && entry.side != side) {
            logger.debug("Addon '{}' runs on {} only; skipped on {}.", entry.id, entry.side, side)
            return
        }
        val context = Context(entry.id, side)
        entry.context = context
        try {
            (entry.addon as? DreamDisplaysAddon)?.onEnable(context)
            binders.forEach { it.bind(entry.addon, context) }
            logger.info("Addon '{}' enabled.", entry.id)
        } catch (t: Throwable) {
            logger.error("Addon '{}' failed to enable and was disabled.", entry.id, t)
            disable(entry)
        }
    }

    private fun disable(entry: Entry) {
        val context = entry.context ?: return
        entry.context = null
        runCatching { (entry.addon as? DreamDisplaysAddon)?.onDisable() }
            .onFailure { logger.error("Addon '{}' failed in onDisable.", entry.id, it) }
        context.close()
    }

    private class Entry(val id: String, val side: AddonSide, val addon: Any) {
        var context: Context? = null
    }

    private class Context(override val id: String, override val side: AddonSide) : AddonContext, AutoCloseable {
        private val owned = ArrayDeque<AutoCloseable>()

        @Synchronized
        override fun <T : AutoCloseable> own(resource: T): T = resource.also { owned.addLast(it) }

        override fun close() {
            val toClose = synchronized(this) { owned.toList().asReversed().also { owned.clear() } }
            toClose.forEach { runCatching { it.close() } }
        }
    }
}
