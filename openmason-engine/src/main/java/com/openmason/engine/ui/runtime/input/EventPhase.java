package com.openmason.engine.ui.runtime.input;

/** Where an event is in its propagation path (Unity UI Toolkit phases). */
public enum EventPhase {
    NONE,
    /** Root towards the target's parent; only handlers registered for this phase run. */
    TRICKLE_DOWN,
    /** At the target: trickle-down handlers first, then bubble-up handlers. */
    AT_TARGET,
    /** Target's parent back to the root; only for bubbling types. */
    BUBBLE_UP
}
