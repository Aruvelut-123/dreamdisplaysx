package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.utils.identifier
import com.dreamdisplays.platform.client.Initializer
//? if >=1.21.11 {
//? if <26.3 {
import com.mojang.blaze3d.pipeline.RenderPipeline
//?}
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier

//?} else
/*import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * Shared unlit world render types for display quads.
 *
 * The display must keep normal depth testing, but it should not go through vanilla block / entity
 * lighting pipelines: shader packs commonly replace those and end up shading the video.
 */
object DisplayUnlitRenderTypes {
    //? if >=1.21.11 {
    private const val SAMPLER_TEXTURE = "Sampler0"

    private val texturedPipeline: RenderPipeline by lazy {
        val pipeline = RenderPipelineCompat.createDisplayPipeline(
            identifier(Initializer.MOD_ID, "pipeline/display_unlit_textured"),
            identifier(Initializer.MOD_ID, "core/display_fog" +
                //? if >=26.3 {
                "_rp"
                //?} else
                /*""*/),
            identifier(Initializer.MOD_ID, "core/display_fog" +
                //? if >=26.3 {
                "_rp"
                //?} else
                /*""*/),
            [SAMPLER_TEXTURE],
        )
        assignIrisTexturedProgram(pipeline)
        pipeline
    }

    /** Creates an unlit [RenderType] named [name] that samples texture [id]. */
    fun create(name: String, id: Identifier): RenderType = DisplaySolidPhase.mark(
        RenderType.create(
            name,
            RenderSetup.builder(texturedPipeline)
                .withTexture(SAMPLER_TEXTURE, id)
                .createRenderSetup(),
        )
    )

    private fun assignIrisTexturedProgram(pipeline: RenderPipeline) {
        runCatching {
            val apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
            val programClass = Class.forName("net.irisshaders.iris.api.v0.IrisProgram")
            val api = apiClass.getMethod("getInstance").invoke(null)

            val textured = RenderPipelineCompat.reflectiveEnumValue(programClass, "TEXTURED")
            apiClass.getMethod("assignPipeline", RenderPipeline::class.java, programClass)
                .invoke(api, pipeline, textured)
        }
    }
    //?} else
    /*
    //? if >=1.21.1 {
    fun create(name: String, id: Identifier): RenderType {
        val state = RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard(java.util.function.Supplier {
                GameRenderer.getPositionTexColorShader()
            }))
            .setTextureState(RenderStateShard.TextureStateShard(id, false, false))
            .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
            .createCompositeState(false)
        val method = RenderType::class.java.declaredMethods.first { m ->
            m.parameterCount == 7 && m.parameterTypes.let { p ->
                p[0] == String::class.java &&
                    p[1] == VertexFormat::class.java &&
                    p[2] == VertexFormat.Mode::class.java &&
                    p[3] == Int::class.javaPrimitiveType &&
                    p[4] == Boolean::class.javaPrimitiveType &&
                    p[5] == Boolean::class.javaPrimitiveType &&
                    p[6].isAssignableFrom(state.javaClass)
            }
        }
        return method.apply { isAccessible = true }.invoke(
            null,
            name,
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            false,
            state,
        ) as RenderType
    }
    //?}
    //? if <1.21.1 {
    fun create(name: String, id: Identifier): RenderType {
        val state = LegacyShards.texturedState(id)
        val method = RenderType::class.java.declaredMethods.first { m ->
            m.parameterCount == 7 && m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == VertexFormat::class.java && m.parameterTypes[6].isAssignableFrom(state.javaClass)
        }
        return method.apply { isAccessible = true }.invoke(
            null, name, DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1536, false, false, state,
        ) as RenderType
    }

    private object LegacyShards : RenderType(
        "dreamdisplays_legacy_shards", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS,
        256, false, false, {}, {},
    ) {
        fun texturedState(id: Identifier): Any = CompositeState.builder()
            .setShaderState(ShaderStateShard { GameRenderer.getPositionTexColorShader() })
            .setTextureState(TextureStateShard(id, false, false))
            .setTransparencyState(NO_TRANSPARENCY)
            .setCullState(NO_CULL)
            .setDepthTestState(LEQUAL_DEPTH_TEST)
            .setLayeringState(POLYGON_OFFSET_LAYERING)
            .createCompositeState(false)
    }
    //?}
    */
}
