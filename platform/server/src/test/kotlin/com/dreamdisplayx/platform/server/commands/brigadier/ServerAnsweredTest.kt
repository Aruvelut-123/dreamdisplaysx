package com.dreamdisplayx.platform.server.commands.brigadier

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.tree.ArgumentCommandNode
import com.mojang.brigadier.tree.CommandNode
import com.dreamdisplayx.platform.server.registrar.BareTokenArgumentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [answeredByServer]: the wrapped tree must expose exactly one vanilla
 * `greedyString` argument so a custom [ArgumentType] never reaches a client, which is what
 * kept `/display fullscreen target` crashing with "Unrecognized argument type" when the
 * custom type was serialised to clients.
 */
class ServerAnsweredTest {

    private fun tree(): CommandNode<Unit> =
        LiteralArgumentBuilder.literal<Unit>("display")
            .then(
                LiteralArgumentBuilder.literal<Unit>("fullscreen")
                    .then(
                        LiteralArgumentBuilder.literal<Unit>("target")
                            .then(RequiredArgumentBuilder.argument<Unit, String>("players", BareTokenArgumentType)),
                    ),
            ).build()

    /** Every argument type reachable from [root], depth first. */
    private fun argumentTypes(root: CommandNode<Unit>): List<ArgumentType<*>> {
        val found = mutableListOf<ArgumentType<*>>()
        val stack = ArrayDeque(listOf(root))
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node is ArgumentCommandNode<*, *>) found += node.type
            stack.addAll(node.children)
        }
        return found
    }

    @Test
    fun theOriginalTreeLeaksTheCustomArgumentType() {
        // Guards the failure this wrapper exists to prevent: without the wrapper the custom type
        // is part of the tree the server serialises, so a client cannot resolve it.
        assertTrue(argumentTypes(tree()).any { it === BareTokenArgumentType })
    }

    @Test
    fun wrappedTreeOnlyExposesTheVanillaGreedyString() {
        val wrapped = tree().answeredByServer()
        val types = argumentTypes(wrapped)
        assertEquals(1, types.size, "the wrapper must hide every original argument node")
        assertEquals(StringArgumentType.greedyString()::class.java, types.single()::class.java)
    }

    @Test
    fun wrappedTreeKeepsTheOriginalChildrenForServerSideCompletion() {
        val wrapped = tree().answeredByServer()
        // The children move into the private completion dispatcher, so the client-facing node
        // keeps only the `<command>` argument while the server still resolves the real branches.
        val command = wrapped.getChild("command")
        assertNotNull(command, "the wrapper must expose a <command> argument node")
        assertEquals(1, wrapped.children.size, "only <command> may remain on the client-facing root")
        assertTrue(wrapped.children.none { it.name == "fullscreen" }, "branches must not stay on the client-facing root")
    }

    @Test
    fun wrappedTreePreservesTheLiteralNameAndRequirement() {
        val original = LiteralArgumentBuilder.literal<Unit>("display").requires { false }.build()
        val wrapped = original.answeredByServer()
        assertEquals("display", wrapped.name)
        assertEquals(original.requirement, wrapped.requirement)
    }

    @Test
    fun everyBareTokenUseIsHiddenBehindTheWrapper() {
        // The `/display` trees build BareTokenArgumentType only inside branches, so wrapping the
        // root literal is enough; this asserts the wrapper drops them all in one step.
        val wrapped = tree().answeredByServer()
        assertTrue(argumentTypes(wrapped).none { it === BareTokenArgumentType })
    }
}