package com.dreamdisplayx.platform.client.render

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import java.util.function.Supplier

/**
 * Shared unlit world render types for display quads.
 *
 * The display must keep normal depth testing, but it should not go through vanilla block / entity
 * lighting pipelines: shader packs commonly replace those and end up shading the video.
 *
 * 1.20.1 has no `RenderPipeline` / `RenderSetup`; the equivalent is a `RenderType.CompositeState`.
 * Three layers of Java access control block the same code the newer targets get for free: the nested
 * shard types are `protected` in `RenderStateShard`, the state constants are `protected` fields, and
 * `RenderType.create(String, VertexFormat, Mode, int, CompositeState)` is package-private. Those
 * members are widened by the 1.20.1 access widener `:platform:client:fabric` generates (see
 * `legacy1_20ClassTweaker` in its build script), so this file is ordinary Kotlin.
 *
 * Reflection is deliberately not an option here even though it works in a dev run: Loom's remapper
 * rewrites bytecode references to `intermediary` but leaves string literals alone, so a reflective
 * `Class.forName("...RenderType$CompositeState")` / `getMethod("builder")` /
 * `getDeclaredField("NO_CULL")` would still ask for the `named` names while the shipped jar only has
 * `class_1921$class_4688` / `method_23598` / `field_21412`. Nested simple names are remapped as well,
 * so matching on `getSimpleName()` fails too. Every reference below is a real bytecode reference and
 * therefore survives remapping.
 */
object DisplayUnlitRenderTypes {
    /**
     * Builds the unlit composite state for [id]; [translucent] opts overlays into blending.
     *
     * The shader is `position_tex_color`, which has no lighting or fog terms, so video texels reach
     * the framebuffer unmodified. Depth test stays LEQUAL so the quad still sorts against terrain.
     */
    private fun compositeState(id: ResourceLocation, translucent: Boolean): RenderType.CompositeState =
        RenderType.CompositeState.builder()
            .setShaderState(
                RenderStateShard.ShaderStateShard(Supplier { GameRenderer.getPositionTexColorShader() }),
            )
            .setTextureState(RenderStateShard.TextureStateShard(id, false, false))
            .setTransparencyState(
                if (translucent) {
                    RenderStateShard.TRANSLUCENT_TRANSPARENCY
                } else {
                    RenderStateShard.NO_TRANSPARENCY
                },
            )
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
            .createCompositeState(false)

    /** Creates an unlit [RenderType]; [translucent] opts overlays into blending. */
    fun create(name: String, id: ResourceLocation, translucent: Boolean = false): RenderType =
        RenderType.create(
            name,
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            compositeState(id, translucent),
        )
}