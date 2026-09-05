package com.dreamdisplayx.platform.server.managers

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Platform-free named-group membership registry. */
internal object GroupRegistry {
    private val groups = ConcurrentHashMap<String, MutableSet<UUID>>()

    fun create(name: String): Boolean = groups.putIfAbsent(name, ConcurrentHashMap.newKeySet()) == null
    fun delete(name: String): Boolean = groups.remove(name) != null
    fun names(): List<String> = groups.keys.toList().sorted()
    fun contains(name: String): Boolean = groups.containsKey(name)
    fun add(name: String, id: UUID): Boolean = groups[name]?.add(id) ?: false
    fun remove(name: String, id: UUID): Boolean = groups[name]?.remove(id) == true
    fun memberIds(name: String): Set<UUID> = groups[name].orEmpty().toSet()
}
