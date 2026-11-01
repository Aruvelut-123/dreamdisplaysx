package com.dreamdisplayx.core.addon

import com.dreamdisplays.api.addon.Addon
import com.dreamdisplays.api.addon.AddonContext
import com.dreamdisplays.api.addon.AddonLifecycle
import com.dreamdisplayx.api.addon.ForkAddon
import com.dreamdisplayx.api.addon.ForkAddonContext
import com.dreamdisplayx.api.addon.ForkAddonLifecycle
import com.dreamdisplayx.api.platform.identity.PlatformSide
import com.dreamdisplayx.api.runtime.registry.service.get
import com.dreamdisplayx.api.runtime.registry.service.register
import com.dreamdisplayx.core.runtime.DefaultServiceRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Covers the fork's dual-family addon host: both annotations, dependency order, failure isolation, teardown. */
class ForkAddonManagerTest {

    /** Records the lifecycle calls it receives, in order. */
    private class Recorder {
        val events = ArrayList<String>()

        fun add(event: String) {
            events += event
        }
    }

    @ForkAddon(id = "fork-basic")
    private class ForkBasic(private val recorder: Recorder) : ForkAddonLifecycle {
        override fun onEnable(context: ForkAddonContext) {
            recorder.add("enable:${context.id}:${context.side}")
        }

        override fun onDisable() = recorder.add("disable:fork-basic")
    }

    @Addon(id = "upstream-basic")
    private class UpstreamBasic(private val recorder: Recorder) : AddonLifecycle {
        override fun onEnable(context: AddonContext) {
            recorder.add("enable:${context.id}:${context.side}")
        }

        override fun onDisable() = recorder.add("disable:upstream-basic")
    }

    @ForkAddon(id = "fork-throwing")
    private class ForkThrowing : ForkAddonLifecycle {
        override fun onEnable(context: ForkAddonContext) = error("boom")
    }

    @ForkAddon(id = "fork-owns")
    private class ForkOwning(private val recorder: Recorder) : ForkAddonLifecycle {
        override fun onEnable(context: ForkAddonContext) {
            context.own(AutoCloseable { recorder.add("close:1") })
            context.own(AutoCloseable { recorder.add("close:2") })
        }
    }

    /** No annotation at all. */
    private class Unannotated

    private fun manager(side: PlatformSide = PlatformSide.CLIENT, binders: List<ForkAddonBinder> = emptyList()) =
        ForkAddonManager(side, binders, { null })

    @Test
    fun forkAddonIsEnabledAndDisabled() {
        val recorder = Recorder()
        val manager = manager()

        manager.register(ForkBasic(recorder))
        manager.start()

        assertEquals(listOf("enable:fork-basic:CLIENT"), recorder.events)
        assertEquals(setOf("fork-basic"), manager.enabledIds)

        manager.stop()

        assertEquals(listOf("enable:fork-basic:CLIENT", "disable:fork-basic"), recorder.events)
        assertTrue(manager.enabledIds.isEmpty())
    }

    @Test
    fun upstreamAddonRunsWithAnUpstreamContext() {
        val recorder = Recorder()
        val manager = manager()

        manager.register(UpstreamBasic(recorder))
        manager.start()

        // The upstream view reports the upstream enum, not the fork's.
        assertEquals(listOf("enable:upstream-basic:CLIENT"), recorder.events)
        assertEquals(setOf("upstream-basic"), manager.enabledIds)
    }

    @Test
    fun dependenciesAreEnabledFirstAndDisabledLast() {
        val recorder = Recorder()

        @ForkAddon(id = "dep-base")
        class Base : ForkAddonLifecycle {
            override fun onEnable(context: ForkAddonContext) = recorder.add("enable:dep-base")

            override fun onDisable() = recorder.add("disable:dep-base")
        }

        @ForkAddon(id = "dep-child", dependsOn = ["dep-base"])
        class Child : ForkAddonLifecycle {
            override fun onEnable(context: ForkAddonContext) = recorder.add("enable:dep-child")

            override fun onDisable() = recorder.add("disable:dep-child")
        }

        val manager = manager()
        // Child registered first on purpose: order must come from dependsOn, not registration order.
        manager.register(Child())
        manager.register(Base())
        manager.start()

        assertEquals(listOf("enable:dep-base", "enable:dep-child"), recorder.events)

        recorder.events.clear()
        manager.stop()

        assertEquals(listOf("disable:dep-child", "disable:dep-base"), recorder.events)
    }

    @Test
    fun missingDependencyStaysDisabled() {
        val manager = manager()

        @ForkAddon(id = "needs-ghost", dependsOn = ["ghost"])
        class NeedsGhost : ForkAddonLifecycle {
            override fun onEnable(context: ForkAddonContext) = Unit
        }

        manager.register(NeedsGhost())
        manager.start()

        assertTrue(manager.enabledIds.isEmpty())
    }

    @Test
    fun dependencyCanCrossAnnotationFamilies() {
        val recorder = Recorder()

        @ForkAddon(id = "cross-child", dependsOn = ["upstream-basic"])
        class CrossChild : ForkAddonLifecycle {
            override fun onEnable(context: ForkAddonContext) = recorder.add("enable:cross-child")
        }

        val manager = manager()
        manager.register(CrossChild())
        manager.register(UpstreamBasic(recorder))
        manager.start()

        assertEquals(setOf("upstream-basic", "cross-child"), manager.enabledIds)
        assertEquals("enable:upstream-basic:CLIENT", recorder.events.first())
    }

    @Test
    fun failingAddonIsIsolated() {
        val recorder = Recorder()
        val manager = manager()

        manager.register(ForkThrowing())
        manager.register(ForkBasic(recorder))
        manager.start()

        assertEquals(setOf("fork-basic"), manager.enabledIds)
        assertEquals(listOf("enable:fork-basic:CLIENT"), recorder.events)
    }

    @Test
    fun ownedResourcesCloseInReverseOrder() {
        val recorder = Recorder()
        val manager = manager()

        manager.register(ForkOwning(recorder))
        manager.start()
        manager.stop()

        assertEquals(listOf("close:2", "close:1"), recorder.events)
    }

    @Test
    fun otherSideIsSkipped() {
        val recorder = Recorder()
        val manager = manager(side = PlatformSide.CLIENT)

        @ForkAddon(id = "server-only", side = PlatformSide.SERVER)
        class ServerOnly : ForkAddonLifecycle {
            override fun onEnable(context: ForkAddonContext) = recorder.add("enable:server-only")
        }

        manager.register(ServerOnly())
        manager.start()

        assertTrue(manager.enabledIds.isEmpty())
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun bothSideAddonRunsOnEitherSide() {
        val recorder = Recorder()

        @ForkAddon(id = "everywhere", side = PlatformSide.BOTH)
        class Everywhere : ForkAddonLifecycle {
            override fun onEnable(context: ForkAddonContext) = recorder.add("enable:${context.id}")
        }

        val manager = manager(side = PlatformSide.SERVER)
        manager.register(Everywhere())
        manager.start()

        assertEquals(setOf("everywhere"), manager.enabledIds)
        assertEquals(listOf("enable:everywhere"), recorder.events)
    }

    @Test
    fun unannotatedAddonIsRejected() {
        assertFailsWith<IllegalArgumentException> { manager().register(Unannotated()) }
    }

    @Test
    fun bothAnnotationsOnOneClassIsRejected() {
        @ForkAddon(id = "duplicated")
        @Addon(id = "duplicated")
        class Duplicated

        assertFailsWith<IllegalArgumentException> { manager().register(Duplicated()) }
    }

    @Test
    fun duplicateIdIsRejected() {
        val manager = manager()
        manager.register(ForkBasic(Recorder()))

        assertFailsWith<IllegalArgumentException> { manager.register(ForkBasic(Recorder())) }
    }

    @Test
    fun selfDependencyIsRejected() {
        @ForkAddon(id = "selfish", dependsOn = ["selfish"])
        class Selfish : ForkAddonLifecycle {
            override fun onEnable(context: ForkAddonContext) = Unit
        }

        assertFailsWith<IllegalArgumentException> { manager().register(Selfish()) }
    }

    @Test
    fun registrationWhileRunningEnablesImmediately() {
        val recorder = Recorder()
        val manager = manager()
        manager.start()

        manager.register(ForkBasic(recorder))

        assertEquals(setOf("fork-basic"), manager.enabledIds)
        assertEquals(listOf("enable:fork-basic:CLIENT"), recorder.events)
    }

    @Test
    fun restartReEnablesRegisteredAddons() {
        val recorder = Recorder()
        val manager = manager()

        manager.register(ForkBasic(recorder))
        manager.start()
        manager.stop()
        manager.start()

        assertEquals(
            listOf("enable:fork-basic:CLIENT", "disable:fork-basic", "enable:fork-basic:CLIENT"),
            recorder.events,
        )
    }

    @Test
    fun contextResolvesRegisteredServices() {
        val registry = DefaultServiceRegistry()
        val service = StringBuilder("live")
        registry.register<CharSequence>(service)

        val manager = ForkAddonManager(PlatformSide.CLIENT, emptyList()) { registry }
        var resolved: CharSequence? = null

        @ForkAddon(id = "service-reader")
        class ServiceReader : ForkAddonLifecycle {
            override fun onEnable(context: ForkAddonContext) {
                resolved = context.service(CharSequence::class.java)
            }
        }

        manager.register(ServiceReader())
        manager.start()

        assertSame(service, resolved)
    }

    @Test
    fun contextReturnsNullForMissingService() {
        val manager = ForkAddonManager(PlatformSide.CLIENT, emptyList()) { DefaultServiceRegistry() }
        var missing: Any? = "not-null-yet"

        @ForkAddon(id = "missing-reader")
        class MissingReader : ForkAddonLifecycle {
            override fun onEnable(context: ForkAddonContext) {
                missing = context.service(Runnable::class.java)
            }
        }

        manager.register(MissingReader())
        manager.start()

        assertNull(missing)
    }

    @Test
    fun binderSeesTheAddonWhenItIsEnabled() {
        val bound = ArrayList<String>()
        val binder = ForkAddonBinder { addon, context ->
            bound += "${addon.javaClass.simpleName}@${context.id}"
        }
        val manager = manager(binders = listOf(binder))

        manager.register(ForkBasic(Recorder()))
        manager.start()

        assertEquals(listOf("ForkBasic@fork-basic"), bound)
        assertFalse(bound.isEmpty())
    }

    @Test
    fun disabledAddonStopsReceivingItsOwnedSubscriptions() {
        val closed = ArrayList<String>()
        val binder = ForkAddonBinder { _, context ->
            context.own(AutoCloseable { closed += "binder-closed" })
        }
        val manager = manager(binders = listOf(binder))

        manager.register(ForkBasic(Recorder()))
        manager.start()
        assertTrue(closed.isEmpty())

        manager.stop()
        assertEquals(listOf("binder-closed"), closed)
    }
}