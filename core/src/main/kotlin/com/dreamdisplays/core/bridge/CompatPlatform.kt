package com.dreamdisplays.core.bridge

import com.dreamdisplays.api.platform.capability.PlatformLogger
import com.dreamdisplays.api.platform.capability.PlatformPaths
import com.dreamdisplays.api.platform.capability.PlatformScheduler
import com.dreamdisplays.api.platform.capability.TaskHandle
import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.api.platform.identity.PlatformId
import com.dreamdisplays.api.platform.identity.PlatformSide
import com.dreamdisplayx.api.platform.capability.PlatformLogger as XPlatformLogger
import com.dreamdisplayx.api.platform.capability.PlatformPaths as XPlatformPaths
import com.dreamdisplayx.api.platform.capability.PlatformScheduler as XPlatformScheduler
import com.dreamdisplayx.api.platform.capability.TaskHandle as XTaskHandle
import com.dreamdisplayx.api.platform.identity.Platform as XPlatform
import com.dreamdisplayx.api.platform.identity.PlatformId as XPlatformId
import com.dreamdisplayx.api.platform.identity.PlatformSide as XPlatformSide

/**
 * Presents the fork's [XPlatform] as the upstream [Platform] an addon expects.
 *
 * Both surfaces carry exactly the same facts (`id`, side, Minecraft/mod version, scheduler, logger, paths, dev flag),
 * so the four nested capability types are wrapped one-for-one. Values are read through on every access instead of
 * being copied, so a later change on the fork side (a mod-version override, a dev flag flipped by the loader) is
 * visible to addons immediately.
 *
 * @since 1.10.x
 */
internal class PlatformBridge(private val delegate: XPlatform) : Platform {
    override val id: String get() = delegate.id

    override val platformId: PlatformId get() = PlatformId.fromWire(delegate.id)

    override val side: PlatformSide get() = delegate.side.toCompat()

    override val minecraftVersion: String get() = delegate.minecraftVersion

    override val modVersion: String get() = delegate.modVersion

    override val scheduler: PlatformScheduler get() = SchedulerBridge(delegate.scheduler)

    override val logger: PlatformLogger get() = LoggerBridge(delegate.logger)

    override val paths: PlatformPaths get() = PathsBridge(delegate.paths)

    override val isDevEnvironment: Boolean get() = delegate.isDevEnvironment
}

/** Maps the fork's platform side onto the upstream enum. */
internal fun XPlatformSide.toCompat(): PlatformSide = when (this) {
    XPlatformSide.CLIENT -> PlatformSide.CLIENT
    XPlatformSide.SERVER -> PlatformSide.SERVER
    XPlatformSide.BOTH -> PlatformSide.BOTH
}

/** Maps the upstream platform side onto the fork enum. */
internal fun PlatformSide.toFork(): XPlatformSide = when (this) {
    PlatformSide.CLIENT -> XPlatformSide.CLIENT
    PlatformSide.SERVER -> XPlatformSide.SERVER
    PlatformSide.BOTH -> XPlatformSide.BOTH
}

/** Maps the fork's typed platform id onto the upstream enum. */
internal fun XPlatformId.toCompat(): PlatformId = PlatformId.fromWire(wire)

/**
 * Scheduler bridge. Every call is forwarded immediately, so ordering and thread affinity stay exactly as the platform
 * enforces them; the returned handles are re-wrapped so an addon only ever holds an upstream [TaskHandle].
 */
internal class SchedulerBridge(private val delegate: XPlatformScheduler) : PlatformScheduler {
    override val isOnMainThread: Boolean get() = delegate.isOnMainThread

    override fun runOnMainThread(task: () -> Unit) = delegate.runOnMainThread(task)

    override fun runAsync(task: () -> Unit): TaskHandle = TaskHandleBridge(delegate.runAsync(task))

    override fun runRepeating(intervalTicks: Long, task: () -> Unit): TaskHandle =
        TaskHandleBridge(delegate.runRepeating(intervalTicks, task))

    override fun runDelayed(delayTicks: Long, task: () -> Unit): TaskHandle =
        TaskHandleBridge(delegate.runDelayed(delayTicks, task))
}

/** Wraps a fork task handle so cancellation reaches the platform scheduler. */
internal class TaskHandleBridge(private val delegate: XTaskHandle) : TaskHandle {
    override fun cancel() = delegate.cancel()
}

/** Forwards to the platform logger; `child` returns another bridge so the whole chain stays upstream-typed. */
internal class LoggerBridge(private val delegate: XPlatformLogger) : PlatformLogger {
    override fun info(message: String) = delegate.info(message)

    override fun warn(message: String) = delegate.warn(message)

    override fun error(message: String, cause: Throwable?) = delegate.error(message, cause)

    override fun debug(message: String) = delegate.debug(message)

    override fun child(name: String): PlatformLogger = LoggerBridge(delegate.child(name))
}

/** Paths are plain `Path` values on both sides, so the bridge only has to forward them. */
internal class PathsBridge(private val delegate: XPlatformPaths) : PlatformPaths {
    override val configDir get() = delegate.configDir

    override val cacheDir get() = delegate.cacheDir

    override val dataDir get() = delegate.dataDir

    override val modDir get() = delegate.modDir
}