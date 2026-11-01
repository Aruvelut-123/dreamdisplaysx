package com.dreamdisplayx.core.addon

import com.dreamdisplays.api.addon.Addon as UpstreamAddon
import com.dreamdisplays.api.addon.AddonContext as UpstreamAddonContext
import com.dreamdisplays.api.addon.AddonLifecycle as UpstreamAddonLifecycle
import com.dreamdisplays.api.addon.AddonSide as UpstreamAddonSide
import com.dreamdisplayx.api.addon.ForkAddon
import com.dreamdisplayx.api.addon.ForkAddonContext
import com.dreamdisplayx.api.addon.ForkAddonLifecycle
import com.dreamdisplayx.api.platform.identity.PlatformSide
import com.dreamdisplayx.api.runtime.registry.service.ServiceRegistry
import org.slf4j.LoggerFactory

/**
 * Metadata every registered addon carries, whichever annotation declared it.
 *
 * [id] is the identity used for logging, the `dependsOn` graph and duplicate detection; it is not translated between
 * the two annotation families, so an addon keeps the id its author wrote.
 */
internal class ForkAddonEntry(
    val id: String,
    val side: PlatformSide,
    val dependsOn: List<String>,
    val addon: Any,
    val kind: Kind,
) {
    /** Which annotation the addon carries; decides which lifecycle callback and context type it receives. */
    enum class Kind { UPSTREAM, FORK }

    var context: ForkAddonHandle? = null
    var failed: Boolean = false
}

/**
 * Hosts addons on one side and accepts **both** annotation families.
 *
 * This manager is the fork's own; it deliberately does not extend `com.dreamdisplays.core.addon.AddonManager`
 * because that one only recognises upstream's `@Addon`, and an addon carrying `@ForkAddon` would be rejected with
 * "must be annotated with @Addon". Instead:
 *
 *  * upstream-annotated addons get an `AddonLifecycle` callback with an `AddonContext` view — identical behaviour to
 *    upstream, which is what keeps pre-compiled addons working;
 *  * fork-annotated addons get a `ForkAddonLifecycle` callback with a `ForkAddonContext`, which additionally exposes
 *    the running side's [ServiceRegistry].
 *
 * Dependency handling, failure isolation and shutdown order are exactly upstream's: an addon is enabled only once
 * every id in its `dependsOn` is enabled, is disabled *before* those dependencies, and a throwing addon is disabled
 * on its own without affecting the others.
 *
 * @since 1.10.x
 */
class ForkAddonManager(
    private val side: PlatformSide,
    private val binders: List<ForkAddonBinder>,
    private val services: () -> ServiceRegistry?,
) {
    private val logger = LoggerFactory.getLogger(ForkAddonManager::class.java)

    private val addons = LinkedHashMap<String, ForkAddonEntry>()

    private val enabledOrder = ArrayList<ForkAddonEntry>()

    private var running = false

    /** IDs of the addons that are enabled right now. */
    val enabledIds: Set<String>
        @Synchronized get() = addons.values.filter { it.context != null }.map { it.id }.toSet()

    /**
     * Registers [addon] and enables it when the host is running.
     *
     * Accepts a class annotated with either `com.dreamdisplays.api.addon.Addon` or
     * `com.dreamdisplayx.api.addon.ForkAddon`; anything else is rejected so a typo fails loudly at registration
     * instead of silently doing nothing.
     */
    @Synchronized
    fun register(addon: Any) {
        val entry = describe(addon)
        require(entry.id !in addons) { "Addon '${entry.id}' is already registered." }
        require(entry.id !in entry.dependsOn) { "Addon '${entry.id}' cannot depend on itself." }
        addons[entry.id] = entry
        if (running) enableReady()
    }

    /** Reads the annotation on [addon] and turns it into an entry; throws when neither annotation is present. */
    private fun describe(addon: Any): ForkAddonEntry {
        val type = addon.javaClass
        val upstream = type.getAnnotation(UpstreamAddon::class.java)
        val fork = type.getAnnotation(ForkAddon::class.java)
        require(!(upstream != null && fork != null)) {
            "${type.name} must carry either @Addon or @ForkAddon, not both."
        }
        if (upstream != null) {
            require(upstream.id.isNotBlank()) { "Addon id must not be blank (${type.name})." }
            return ForkAddonEntry(
                id = upstream.id,
                side = upstream.side.toForkSide(),
                dependsOn = upstream.dependsOn.toList(),
                addon = addon,
                kind = ForkAddonEntry.Kind.UPSTREAM,
            )
        }
        if (fork != null) {
            require(fork.id.isNotBlank()) { "Addon id must not be blank (${type.name})." }
            return ForkAddonEntry(
                id = fork.id,
                side = fork.side,
                dependsOn = fork.dependsOn.toList(),
                addon = addon,
                kind = ForkAddonEntry.Kind.FORK,
            )
        }
        throw IllegalArgumentException(
            "${type.name} must be annotated with @Addon (upstream) or @ForkAddon (Dream DisplaysX) to be registered.",
        )
    }

    /** Starts the host and enables every registered addon that runs on this side. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        enableReady()
        reportBlocked()
    }

    /** Disables every enabled addon, dependents first; registrations survive for a later [start]. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        enabledOrder.toList().asReversed().forEach(::disable)
        addons.values.forEach { it.failed = false }
    }

    /** Enables ready addons until nothing more can be enabled. */
    private fun enableReady() {
        do {
            val next = addons.values
                .firstOrNull { it.runsHere && it.context == null && !it.failed && it.dependenciesEnabled() }
                ?.also(::enable)
        } while (next != null)
    }

    private fun ForkAddonEntry.dependenciesEnabled(): Boolean = dependsOn.all { addons[it]?.context != null }

    private val ForkAddonEntry.runsHere: Boolean
        get() = side == PlatformSide.BOTH || this.side == PlatformSide.BOTH || this.side == this@ForkAddonManager.side

    /** Warns once about every addon that could not be enabled and why, after the first enable sweep. */
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

    private fun enable(entry: ForkAddonEntry) {
        val handle = ForkAddonHandle(entry.id, side, services)
        entry.context = handle
        enabledOrder += entry
        try {
            when (entry.kind) {
                ForkAddonEntry.Kind.UPSTREAM ->
                    (entry.addon as? UpstreamAddonLifecycle)?.onEnable(UpstreamContextView(handle))
                ForkAddonEntry.Kind.FORK ->
                    (entry.addon as? ForkAddonLifecycle)?.onEnable(handle)
            }
            binders.forEach { it.bind(entry.addon, handle) }
            logger.info("Addon '{}' enabled on {}.", entry.id, side)
        } catch (t: Throwable) {
            logger.error("Addon '{}' failed to enable and was disabled.", entry.id, t)
            entry.failed = true
            disable(entry)
        }
    }

    private fun disable(entry: ForkAddonEntry) {
        val handle = entry.context ?: return
        addons.values.filter { entry.id in it.dependsOn && it.context != null }.forEach { dependent ->
            logger.warn("Addon '{}' was disabled because '{}' was.", dependent.id, entry.id)
            disable(dependent)
        }
        entry.context = null
        enabledOrder -= entry
        val disabled = runCatching {
            when (entry.kind) {
                ForkAddonEntry.Kind.UPSTREAM -> (entry.addon as? UpstreamAddonLifecycle)?.onDisable()
                ForkAddonEntry.Kind.FORK -> (entry.addon as? ForkAddonLifecycle)?.onDisable()
            }
        }
        disabled.onFailure { logger.error("Addon '{}' failed in onDisable.", entry.id, it) }
        handle.close()
    }

    /** Maps upstream's `AddonSide` onto the fork's `PlatformSide`; the two enums have identical cases. */
    private fun UpstreamAddonSide.toForkSide(): PlatformSide = when (this) {
        UpstreamAddonSide.CLIENT -> PlatformSide.CLIENT
        UpstreamAddonSide.SERVER -> PlatformSide.SERVER
        UpstreamAddonSide.BOTH -> PlatformSide.BOTH
    }
}

/** Subscribes the annotated methods of an addon; everything subscribed must be handed to `context.own`. */
fun interface ForkAddonBinder {
    /** Binds [addon]'s handlers, registering every subscription with [context]. */
    fun bind(addon: Any, context: ForkAddonContext)
}

/**
 * The live [ForkAddonContext] for one enabled addon.
 *
 * An upstream-annotated addon receives an [UpstreamContextView] over the same handle instead, so both families share
 * one teardown path: [close] drains everything `own` collected, newest first.
 */
internal class ForkAddonHandle(
    override val id: String,
    override val side: PlatformSide,
    private val services: () -> ServiceRegistry?,
) : ForkAddonContext, AutoCloseable {

    private val logger = LoggerFactory.getLogger("DreamDisplaysX/Addon/$id")

    private val owned = ArrayDeque<AutoCloseable>()

    override fun <T : AutoCloseable> own(resource: T): T = resource.also {
        synchronized(owned) { owned.addLast(it) }
    }

    override fun <T : Any> service(type: Class<T>): T? = services()?.getOrNull(type)

    override fun info(message: String) = logger.info(message)

    override fun warn(message: String) = logger.warn(message)

    override fun error(message: String, cause: Throwable?) = logger.error(message, cause)

    override fun close() {
        val toClose = synchronized(owned) { owned.toList().asReversed().also { owned.clear() } }
        toClose.forEach { runCatching { it.close() }.onFailure { logger.warn("Closing an owned resource failed.", it) } }
    }
}

/** Maps the fork's `PlatformSide` onto upstream's `AddonSide` for the upstream context view. */
internal fun PlatformSide.toUpstreamSide(): UpstreamAddonSide = when (this) {
    PlatformSide.CLIENT -> UpstreamAddonSide.CLIENT
    PlatformSide.SERVER -> UpstreamAddonSide.SERVER
    PlatformSide.BOTH -> UpstreamAddonSide.BOTH
}

/**
 * Presents a [ForkAddonHandle] as upstream's `AddonContext`.
 *
 * Only the three upstream members are visible through it — `own` is shared with the fork view, so a resource owned
 * through either interface is closed exactly once, in the same reverse order.
 */
internal class UpstreamContextView(private val handle: ForkAddonHandle) : UpstreamAddonContext {
    override val id: String get() = handle.id

    override val side: UpstreamAddonSide get() = handle.side.toUpstreamSide()

    override fun <T : AutoCloseable> own(resource: T): T = handle.own(resource)
}