package com.dreamdisplayx.platform.client.core.modules

import com.dreamdisplayx.api.media.audio.service.keys.AudioAcousticsServices
import com.dreamdisplayx.api.runtime.module.DreamDisplaysXModule
import com.dreamdisplayx.api.runtime.module.ModuleContext
import com.dreamdisplayx.media.audio.engine.AcousticsEngine
import com.dreamdisplayx.platform.client.managers.ClientStateManager

/** Installs the 3D acoustics engine and seeds it with the current config (quality tier, output profile). */
object ClientAudioModule : DreamDisplaysXModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplayx:client_audio"

    /** Creates the [AcousticsEngine] and registers it under [AudioAcousticsServices.ACOUSTICS]. */
    override fun install(context: ModuleContext) {
        val engine = AcousticsEngine()
        engine.setGlobalQuality(ClientStateManager.config.audioAcoustics)
        engine.setBinauralOutput(ClientStateManager.config.audioBinauralOutput)
        context.services.register(AudioAcousticsServices.ACOUSTICS, engine)
    }
}
