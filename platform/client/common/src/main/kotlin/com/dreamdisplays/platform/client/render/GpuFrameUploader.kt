package com.dreamdisplays.platform.client.render

//? if >=1.21.11 {
//?}
import com.dreamdisplays.api.media.model.FramePixelFormat
import com.dreamdisplays.api.media.player.FrameUploader
import com.dreamdisplays.api.media.player.GpuTextureRef
import net.minecraft.client.Minecraft
import java.nio.ByteBuffer

/**
 * Minecraft GPU upload sink for one decode channel. Holds the persistent [AsyncTextureUploader]
 * PBO ring(s) and performs the actual texture uploads via [TextureUploadUtil], keeping all
 * rendering-API code out of the platform-agnostic media/player module.
 */
class GpuFrameUploader : FrameUploader {
    private var uploader: AsyncTextureUploader? = null
    private val planeUploaders = arrayOfNulls<AsyncTextureUploader>(3)
    private var rgbaUploadBuffer: ByteBuffer? = null
    private val interleavedUploader: () -> AsyncTextureUploader = { glUploader() }
    private val planarUploader: () -> AsyncTextureUploader = { planeUploader(0) }
    private val rgbaScratchSetter: (ByteBuffer) -> Unit = { rgbaUploadBuffer = it }

    override fun canUpload(): Boolean =
        //? if >=1.21.11 {
        //? if >=26.3 {
        !Minecraft.getInstance().window.isIconified
        //?} else
        /*!Minecraft.getInstance().window.isMinimized*/
    //?} else
    /*true*/

    override fun uploadInterleaved(target: GpuTextureRef, src: ByteBuffer, format: FramePixelFormat): Boolean {
        //? if >=1.21.11 {
        val texture = (target as GpuTextureHandle).texture
        TextureUploadUtil.upload(
            texture = texture,
            src = src,
            w = texture.getWidth(0),
            h = texture.getHeight(0),
            format = format.toUploadFormat(),
            glUploader = interleavedUploader,
            rgbaScratch = rgbaUploadBuffer,
            setRgbaScratch = rgbaScratchSetter,
        )
        //?} else
        /*val texture = target as GpuTextureHandle
        glUploader().upload(texture.textureId, src, texture.width, texture.height, format.toUploadFormat())*/
        return true
    }

    override fun uploadPlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, src: ByteBuffer): Boolean {
        //? if >=1.21.11 {
        TextureUploadUtil.uploadPlanar(
            y = (y as GpuTextureHandle).texture,
            u = (u as GpuTextureHandle).texture,
            v = (v as GpuTextureHandle).texture,
            src = src,
            glUploader = planarUploader,
        )
        return true
        //?} else
        /*return false*/
    }

    private fun glUploader(): AsyncTextureUploader =
        uploader ?: AsyncTextureUploader(stateCache = true).also { uploader = it }

    private fun planeUploader(i: Int): AsyncTextureUploader =
        planeUploaders[i] ?: AsyncTextureUploader(stateCache = true).also { planeUploaders[i] = it }

    override fun cleanup() {
        uploader?.cleanup()
        uploader = null
        for (i in planeUploaders.indices) {
            planeUploaders[i]?.cleanup()
            planeUploaders[i] = null
        }
        rgbaUploadBuffer = null
    }
}
