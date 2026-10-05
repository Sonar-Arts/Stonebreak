package com.openmason.engine.ui.runtime.input;

/**
 * Why the router abandoned in-flight interactions (#288): pointer capture, press, drag and
 * drop, key repeat, composition and tooltips end at once, and nothing completes late.
 */
public enum CancelReason {
    /** The cancel action during a drag. */
    ESCAPE,
    /** The drag source left the tree, was disabled or collapsed. */
    SOURCE_REMOVED,
    /** The element accepting the drop left the tree, was disabled or collapsed. */
    TARGET_REMOVED,
    /** A drop target refused the payload in its drop handler. */
    REJECTED,
    /** The connection to the world ended (multiplayer disconnect, world unload). */
    DISCONNECT,
    /** The window lost focus: the platform will not deliver the matching releases. */
    WINDOW_FOCUS_LOST,
    /** The screen hosting the document closed. */
    SCREEN_CLOSED,
    /** Host or script code cancelled. */
    PROGRAM
}
