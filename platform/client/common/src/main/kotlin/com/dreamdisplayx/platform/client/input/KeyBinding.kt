package com.dreamdisplayx.platform.client.input

/**
 * Represents a key binding.
 */
data class KeyBinding(
    /** The unique identifier for this key binding, used for registration and lookup. */
    val id: String,

    /** The default key code for this key binding. */
    val defaultKey: Int,
    /** The category of this key binding, used for grouping in the control's menu. */
    val category: String,
    /** A human-readable description of this key binding, shown in the control's menu. */
    val description: String,
    /**
     * Whether [defaultKey] is a mouse button rather than a keyboard keysym.
     *
     * The value spaces differ (GLFW buttons are 0-based, SDL's are mask-based, keysyms are unrelated),
     * so the platform layer needs to know which `InputConstants.Type` to build the vanilla mapping
     * with. Bindings that already exist only in [KeyBindingRegistry] default to a keyboard key.
     */
    val isMouse: Boolean = false,
)
