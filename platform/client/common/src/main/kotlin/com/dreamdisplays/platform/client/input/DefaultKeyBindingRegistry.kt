package com.dreamdisplays.platform.client.input

import java.util.concurrent.ConcurrentHashMap

/**
 * Default [KeyBindingRegistry]: an id-keyed map of the mod's logical input bindings. Pollers
 * (e.g. the tick handler) look bindings up by id instead of hardcoding `GLFW` codes, so a binding
 * can be redefined in one place.
 */
class DefaultKeyBindingRegistry : KeyBindingRegistry {
    private val bindings = ConcurrentHashMap<String, KeyBinding>()

    override fun register(binding: KeyBinding): KeyBinding = bindings.putIfAbsent(binding.id, binding) ?: binding

    override fun getAll(): List<KeyBinding> = bindings.values.toList()

    override fun findById(id: String): KeyBinding? = bindings[id]

    override fun reset() = bindings.clear()
}
