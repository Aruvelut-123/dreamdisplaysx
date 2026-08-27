package com.dreamdisplayx.media.player

import kotlin.math.abs

/** Combines the user-set volume with distance-based attenuation and pushes the effective value to the audio pipeline. */
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
        applyVolume(userVolume * lastAttenuation)
    }

    /**
     * Recomputes quadratic distance attenuation for [distance] against [maxRadius] and re-applies
     * the effective volume only when the attenuation changed materially.
     */
    fun updateAttenuation(distance: Double, maxRadius: Double) {
        // Gentle linear-ish rolloff with a floor so audio never becomes inaudible: the old
        // quadratic curve dropped volume to ~8% at moderate distances, which made libvlc audio
        // seem completely broken (and libvlc's master clock hangs when a track is silent).
        val t = (1.0 - minOf(1.0, distance / maxRadius)).coerceAtLeast(0.0)
        val attenuation = 0.25 + 0.75 * (t * t)
        if (abs(attenuation - lastAttenuation) > 1e-5) {
            lastAttenuation = attenuation
            applyVolume(userVolume * attenuation)
        }
    }
}
