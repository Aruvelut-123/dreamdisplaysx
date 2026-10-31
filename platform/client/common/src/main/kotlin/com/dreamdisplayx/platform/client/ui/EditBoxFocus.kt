package com.dreamdisplayx.platform.client.ui

import net.minecraft.client.gui.components.EditBox

/**
 * Keyboard-focus helpers for [EditBox].
 *
 * `AbstractWidget.setFocused(boolean)` only became public in 1.19.4, and `EditBox` has no public
 * focus accessor before 1.21.1 (the legacy `EditBox.setFocus` / `isFocused()` pair), so
 * call sites cannot touch either directly on every version.
 */
fun editBoxSetFocused(box: EditBox, focused: Boolean) {
    //? if >=1.19.4 {
    box.setFocused(focused)
    //?} else
    /*box.setFocus(focused)*/
}

/** True while [box] owns keyboard focus. */
fun editBoxHasFocus(box: EditBox): Boolean =
    //? if >=1.21.1 {
    box.isFocused
    //?} else
    /*box.isFocused()*/
