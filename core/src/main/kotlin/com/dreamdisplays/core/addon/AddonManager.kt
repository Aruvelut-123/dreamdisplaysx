package com.dreamdisplays.core.addon

import com.dreamdisplays.api.addon.Addon
import com.dreamdisplays.api.addon.AddonContext
import com.dreamdisplays.api.addon.AddonSide
import com.dreamdisplays.api.addon.AddonLifecycle
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
 *
 * An addon is enabled only after every addon in its `dependsOn` is enabled, and is disabled before any of them. When
 * a dependency fails, is missing or does not run on this side, the dependents stay disabled.
 */
class AddonManager(
    private val side: AddonSide,
    private val binders: List<AddonBinder>,
) {
    private val logger = LoggerFactory.getLogger(AddonManager::class.java)
    private val addons = LinkedHashMap<String, Entry>()
    private val enabledOrder = ArrayList<Entry>()
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
        require(meta.id !in meta.dependsOn) { "Addon '${meta.id}' cannot depend on itself." }
        addons[meta.id] = Entry(meta.id, meta.side, meta.dependsOn.toList(), addon)
        if (running) enableReady()
    }

    /** Starts the host: enables every registered addon that runs on this side. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        enableReady()
        reportBlocked()
    }

    /**
     * Stops the host: disables every enabled addon, dependents first and otherwise in reverse order.
     *
     * Registrations stay for a later [start].
     */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        enabledOrder.toList().asReversed().forEach(::disable)
        addons.values.forEach { it.failed = false }
    }

    /** Enables addons whose dependencies are enabled until nothing more can be enabled. */
    private fun enableReady() {
        do {
            val next = addons.values.firstOrNull { it.runsHere && it.context == null && !it.failed && it.dependenciesEnabled() }
                ?.also(::enable)
        } while (next != null)
    }

    private fun Entry.dependenciesEnabled(): Boolean = dependsOn.all { addons[it]?.context != null }

    private val Entry.runsHere: Boolean get() = this.side == AddonSide.BOTH || this.side == this@AddonManager.side

    private fun reportBlocked() {
        for (entry in addons.values) {
            if (!entry.runsHere) {
                logger.debug("Addon '{}' runs on {} only; skipped on {}.", entry.id, entry.side, side)
            } else if (entry.context == null && !entry.failed) {
                val blockers = entry.dependsOn.filter { addons[it]?.context == null }
                logger.warn("Addon '{}' was not enabled: it needs {} to be enabled first.", entry.id, blockers)
            }
        }
    }

    private fun enable(entry: Entry) {
        val context = Context(entry.id, side)
        entry.context = context
        enabledOrder += entry
        try {
            (entry.addon as? AddonLifecycle)?.onEnable(context)
            binders.forEach { it.bind(entry.addon, context) }
            logger.info("Addon '{}' enabled.", entry.id)
        } catch (t: Throwable) {
            logger.error("Addon '{}' failed to enable and was disabled.", entry.id, t)
            entry.failed = true
            disable(entry)
        }
    }

    private fun disable(entry: Entry) {
        val context = entry.context ?: return
        addons.values.filter { entry.id in it.dependsOn && it.context != null }.forEach { dependent ->
            logger.warn("Addon '{}' was disabled because '{}' was.", dependent.id, entry.id)
            disable(dependent)
        }
        entry.context = null
        enabledOrder -= entry
        runCatching { (entry.addon as? AddonLifecycle)?.onDisable() }
            .onFailure { logger.error("Addon '{}' failed in onDisable.", entry.id, it) }
        context.close()
    }

    private class Entry(val id: String, val side: AddonSide, val dependsOn: List<String>, val addon: Any) {
        var context: Context? = null
        var failed = false
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
