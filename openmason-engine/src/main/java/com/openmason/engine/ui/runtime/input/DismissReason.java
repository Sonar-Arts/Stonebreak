package com.openmason.engine.ui.runtime.input;

/** Why a popup closed. */
public enum DismissReason {
    /** A pointer press outside every open popup; the press is consumed. */
    OUTSIDE_POINTER,
    /** The cancel action (Escape, controller B). */
    CANCEL,
    /** Focus moved outside the popup. */
    FOCUS_LEFT,
    /** The popup's element was hidden, collapsed or removed by the document itself. */
    HIDDEN,
    /** The host cancelled interactions ({@link CancelReason}). */
    INTERACTION_CANCELLED,
    /** Host or script code closed it. */
    PROGRAM
}
