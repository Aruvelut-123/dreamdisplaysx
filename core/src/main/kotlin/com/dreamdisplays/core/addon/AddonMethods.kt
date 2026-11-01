package com.dreamdisplays.core.addon

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * An annotated addon method and the values it may receive. Only the addon's own methods are reflected, never the
 * game's, so this is safe in remapped jars.
 */
internal class AddonMethod(private val target: Any, private val method: Method) {
    /** Name for error messages. */
    val description: String get() = "${target.javaClass.name}.${method.name}"

    init {
        method.isAccessible = true
    }

    /** Checks that every parameter can be filled from [available]; fails with a clear message otherwise. */
    fun requireParameters(annotation: String, vararg available: Class<*>) {
        val params = method.parameterTypes
        require(params.size <= available.size && params.indices.all { params[it].isAssignableFrom(available[it]) }) {
            "$description is annotated with @$annotation, so it must take " +
                "(${available.joinToString { it.simpleName }}) or a leading part of it."
        }
    }

    /** Calls the method with the leading part of [args] that it asks for. Exceptions from the addon propagate. */
    fun call(vararg args: Any?) {
        try {
            method.invoke(target, *args.copyOf(method.parameterCount))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}

/** Finds methods carrying [annotation] on [addon]'s class and its superclasses. */
internal fun <A : Annotation> findAnnotated(addon: Any, annotation: Class<A>): List<AddonMethod> {
    val found = ArrayList<AddonMethod>()
    var type: Class<*>? = addon.javaClass
    while (type != null && type != Any::class.java) {
        type.declaredMethods
            .filter { it.isAnnotationPresent(annotation) && !it.isSynthetic }
            .forEach { found += AddonMethod(addon, it) }
        type = type.superclass
    }
    return found
}
