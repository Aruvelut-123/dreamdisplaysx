package com.dreamdisplayx.media.player

import kotlin.math.abs

/**
 * Combines the user-set volume with distance-based attenuation and pushes the effective value to the
 * audio pipeline.
 *
 * libvlc keeps its software volume **per audio-output instance**. The Android OpenSL ES `aout` is
 * rebuilt whenever the input seeks or the media is re-attached (18 rebuilds in one observed playback
 * log), and a freshly built `integer_mixer` starts at its own default level: every value pushed
 * through `libvlc_audio_set_volume` before that rebuild is lost. The session manager therefore
 * re-asserts the last effective volume on every PLAYING event through [forceApply]. This class stays
 * pure arithmetic so it can be unit-tested without libvlc.
 */
internal class VolumeController(
    initialVolume: Double,
    private val applyVolume: (Double) -> Unit,
) {
    @Volatile
    private var userVolume = initialVolume

    @Volatile
    private var lastAttenuation = 1.0

    /** Sets the user-controlled volume (clamped to 0.0-2.0) and re-applies the effective value. */
    fun setUserVolume(volume: Float) {
        userVolume = volume.toDouble().coerceIn(0.0, 2.0)
        applyEffective()
    }

    /**
     * Recomputes distance attenuation for [distance] against [maxRadius] and re-applies the
     * effective volume only when the attenuation changed materially.
     */
    fun updateAttenuation(distance: Double, maxRadius: Double) {
        val attenuation = attenuationFor(distance, maxRadius)
        if (abs(attenuation - lastAttenuation) > 1e-5) {
            lastAttenuation = attenuation
            applyEffective()
        }
    }

    /**
     * Re-sends the current effective volume without changing any state. Used after libvlc rebuilt
     * its audio output, which silently reset the software mixer we had configured.
     */
    fun forceApply() {
        applyEffective()
    }

    /** The value most recently handed to [applyVolume]; exposed for diagnostics. */
    fun currentEffective(): Double = userVolume * lastAttenuation

    private fun applyEffective() {
        applyVolume(userVolume * lastAttenuation)
    }

    companion object {
        /**
         * Gentle falloff so a nearby display never sounds muffled: full volume next to the screen,
         * tapering to a 50% floor at the edge of the audio radius. The previous `0.25 + 0.75 * t^2`
         * curve already sat at ~67% a quarter of the way out and bottomed out at 25%, which made
         * walking around a display sound like the volume knob was being turned down.
         */
        fun attenuationFor(distance: Double, maxRadius: Double): Double {
            if (!maxRadius.isFinite() || maxRadius <= 0.0) return 1.0
            val t = (1.0 - minOf(1.0, distance / maxRadius)).coerceAtLeast(0.0)
            return 0.5 + 0.5 * t * t
        }
    }
}
