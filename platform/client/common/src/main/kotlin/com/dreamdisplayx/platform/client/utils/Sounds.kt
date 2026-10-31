package com.dreamdisplayx.platform.client.utils

import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents

/** The vanilla button click; a registry holder from 1.19.3, a plain [SoundEvent] before. */
fun buttonClickSound(): SoundEvent =
    //? if >=1.19.3 {
    SoundEvents.UI_BUTTON_CLICK.value()
    //?} else
    /*SoundEvents.UI_BUTTON_CLICK*/
