package com.dreamdisplayx.media.player.managers

/**
 * Decides how a 0..200 percent mixer level and a mute flag map onto libvlc's two audio knobs.
 *
 * ## Why this is not just `set_volume(percent)`
 *
 * `libvlc_audio_set_volume` is a property of the **audio-output instance**, not of the media player.
 * The Android OpenSL ES `aout` is rebuilt on every DASH fragment seek and every media re-attach; a
 * field log of one 47-second Bilibili video contains **19** `creating audio output` lines against
 * **zero** `destroying audio output` lines, and the `integer_mixer` module is only instantiated on the
 * `reusing audio output` path (6 of 19). Every value pushed into a freshly built aout therefore lands
 * in a mixer that does not exist yet, and the aout's own default takes over.
 *
 * The consequence the tester reported is exact: pressing the mute button or dragging the slider to 0 %
 * "does nothing", while the sound level wanders as the OS re-arbitrates the audio route between the
 * earpiece and the speaker on each AudioTrack rebuild.
 *
 * `libvlc_audio_set_mute` is stored on the media player and re-applied by libvlc to whichever aout is
 * current, so it survives a rebuild. Expressing silence through **mute** instead of through
 * `volume = 0` makes both the mute button and a 0 % slider work, and lets a non-zero level be pushed
 * through [mixerPercent] without the level being re-interpreted as "unmute".
 *
 * Kept as a pure object so the arithmetic is unit-testable; the JNA calls stay in
 * [LibVlcSessionManager].
 */
internal object LibVlcMutePolicy {

    /** libvlc's own ceiling for `libvlc_audio_set_volume`, in percent. */
    const val MAX_PERCENT = 200

    /**
     * Fraction of a percent below which the level is treated as silence.
     *
     * `DisplayScreen.applyEffectiveVolume` passes `volume * globalAudioMultiplier` as a `Float`, so a
     * slider at 1 % times a 0.5 global multiplier arrives as `0.005` and rounds to percent `0`. The
     * UI's "0 %" stop must be silence, but a slider that is merely almost-closed should still be
     * audible rather than snapping to mute — hence the threshold is on the *level*, not the rounded
     * percent.
     */
    private const val SILENCE_EPSILON = 1e-4

    /**
     * True when [level] (0.0..2.0, already including distance attenuation) means "no sound".
     *
     * A non-finite or negative level is silence; anything at or above [SILENCE_EPSILON] is audible.
     */
    fun isSilent(level: Double): Boolean = !level.isFinite() || level < SILENCE_EPSILON

    /**
     * libvlc mixer percentage for [level].
     *
     * Silence maps to [MAX_PERCENT] rather than 0 on purpose: the level is expressed through
     * `libvlc_audio_set_mute`, and pushing 0 into the mixer as well would leave a muted player that
     * still reports `get_volume() == 0` after an unmute — the `verifyVolume` poll would then see a
     * "drift", restore 0, and the display would stay silent until a replay. Keeping the mixer at full
     * scale means unmuting is a single boolean flip.
     */
    fun mixerPercent(level: Double): Int {
        if (isSilent(level)) return MAX_PERCENT
        return (level.coerceIn(0.0, 2.0) * 100).toInt().coerceIn(1, MAX_PERCENT)
    }

    /** 1 when the player must be muted, 0 otherwise. */
    fun muteFlag(level: Double): Int = if (isSilent(level)) 1 else 0
}
