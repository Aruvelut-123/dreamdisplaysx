package com.dreamdisplayx.platform.client.render

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.function.Supplier

/**
 * Shared unlit world render types for display quads.
 *
 * The display must keep normal depth testing, but it should not go through vanilla block / entity
 * lighting pipelines: shader packs commonly replace those and end up shading the video.
 *
 * 1.20.1 has no `RenderPipeline` / `RenderSetup`; the equivalent is a `RenderType.CompositeState`.
 * Three layers of Java access control block a direct port from outside the vanilla package: the
 * nested shard types are `protected` in `RenderStateShard`, the state constants are `protected`
 * fields, and `RenderType.create(String, VertexFormat, Mode, int, CompositeState)` is
 * package-private (the 7-arg overload is `private`). Kotlin refuses to even name the protected types,
 * so every step below goes through reflection. Constructors, methods and constants are resolved once
 * and cached, leaving a single reflective `invoke` per call in steady state.
 */
object DisplayUnlitRenderTypes {
    private val shardClass: Class<*> = RenderStateShard::class.java

    /** `RenderType$CompositeState` is a protected nested type, so it is resolved by name. */
    private val compositeStateClass: Class<*> =
        Class.forName("net.minecraft.client.renderer.RenderType\$CompositeState")
    private val builderClass: Class<*> =
        Class.forName("net.minecraft.client.renderer.RenderType\$CompositeState\$CompositeStateBuilder")

    private val builderFactory: Method = compositeStateClass.getMethod("builder")
    private val createCompositeState: Method =
        compositeStateClass.getMethod("createCompositeState", Boolean::class.javaPrimitiveType)

    /** `RenderType.create(String, VertexFormat, Mode, int, CompositeState)` - package-private. */
    private val createMethod: Method = RenderType::class.java.declaredMethods
        .first { it.name == "create" && it.parameterCount == 5 }
        .apply { isAccessible = true }

    /** Protected state constants, resolved and cached per field name. */
    private val constants = HashMap<String, Any>()

    private fun constant(name: String): Any = constants.getOrPut(name) {
        shardClass.getDeclaredField(name).apply { isAccessible = true }.get(null)
            ?: error("RenderStateShard.$name was null")
    }

    /** `CompositeStateBuilder` single-argument setters, cached per method name. */
    private val setters = HashMap<String, Method>()

    private fun applySetter(builder: Any, name: String, value: Any) {
        setters.getOrPut(name) {
            builderClass.methods.first { it.name == name && it.parameterCount == 1 }
        }.invoke(builder, value)
    }

    /** Nested `RenderStateShard` classes, cached by simple name. */
    private val nested = HashMap<String, Class<*>>()

    private fun nested(name: String): Class<*> = nested.getOrPut(name) {
        shardClass.declaredClasses.first { it.simpleName == name }
    }

    private val shaderCtor: Constructor<*> by lazy {
        nested("ShaderStateShard").getConstructor(Supplier::class.java)
    }

    private val textureCtor: Constructor<*> by lazy {
        nested("TextureStateShard").getConstructor(
            ResourceLocation::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    }

    /**
     * Builds the unlit composite state for [id]; [translucent] opts overlays into blending.
     *
     * The shader is `position_tex_color`, which has no lighting or fog terms, so video texels reach
     * the framebuffer unmodified. Depth test stays LEQUAL so the quad still sorts against terrain.
     */
    private fun compositeState(id: ResourceLocation, translucent: Boolean): Any {
        val builder = builderFactory.invoke(null) ?: error("CompositeState.builder() returned null")
        applySetter(builder, "setShaderState", shaderCtor.newInstance(Supplier { GameRenderer.getPositionTexColorShader() }))
        applySetter(builder, "setTextureState", textureCtor.newInstance(id, false, false))
        applySetter(
            builder,
            "setTransparencyState",
            constant(if (translucent) "TRANSLUCENT_TRANSPARENCY" else "NO_TRANSPARENCY"),
        )
        applySetter(builder, "setCullState", constant("NO_CULL"))
        applySetter(builder, "setDepthTestState", constant("LEQUAL_DEPTH_TEST"))
        applySetter(builder, "setLayeringState", constant("POLYGON_OFFSET_LAYERING"))
        return createCompositeState.invoke(builder, false) ?: error("createCompositeState returned null")
    }

    /** Creates an unlit [RenderType]; [translucent] opts overlays into blending. */
    fun create(name: String, id: ResourceLocation, translucent: Boolean = false): RenderType =
        createMethod.invoke(
            null,
            name,
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            compositeState(id, translucent),
        ) as RenderType
}