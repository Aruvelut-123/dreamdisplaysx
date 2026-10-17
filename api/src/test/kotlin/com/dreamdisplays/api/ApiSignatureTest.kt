package com.dreamdisplays.api

import com.dreamdisplays.api.addon.AddonContext
import com.dreamdisplays.api.addon.DreamDisplaysAddon
import com.dreamdisplays.api.server.DreamDisplaysServer
import com.dreamdisplays.api.server.ServerDisplay
import com.dreamdisplays.api.server.ServerDisplayEvent
import com.dreamdisplays.api.server.ServerDisplays
import java.lang.reflect.Method
import java.lang.reflect.Type
import kotlin.test.Test
import kotlin.test.assertTrue

class ApiSignatureTest {
    private val types = listOf(
        DreamDisplays::class.java, Display::class.java, Displays::class.java, Session::class.java,
        DisplayLifecycle::class.java, DisplayBudget::class.java, CommandResult::class.java,
        AddonContext::class.java, DreamDisplaysAddon::class.java,
        DreamDisplaysServer::class.java, ServerDisplay::class.java, ServerDisplays::class.java,
        ServerDisplayEvent::class.java,
    )

    @Test
    fun `addon-facing signatures contain no Kotlin runtime types`() {
        val offenders = types.flatMap { type -> type.declaredMethods.filter(::isPublic).flatMap { offences(type, it) } }
        assertTrue(offenders.isEmpty(), "Kotlin types in public API signatures:\n" + offenders.joinToString("\n"))
    }

    private val allowed = setOf("DreamDisplays.session", "CommandResult.onStale", "CommandResult.getEntries")

    private fun isPublic(method: Method) = java.lang.reflect.Modifier.isPublic(method.modifiers) && !method.isSynthetic

    private fun offences(owner: Class<*>, method: Method): List<String> =
        (listOf(method.genericReturnType) + method.genericParameterTypes)
            .filter { mentionsKotlin(it) }
            .map { "${owner.simpleName}.${method.name}: $it" }
            .filterNot { line -> allowed.any { line.startsWith("$it:") } }

    private fun mentionsKotlin(type: Type): Boolean = type.typeName.contains("kotlin.")
}
