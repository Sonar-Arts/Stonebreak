package com.openmason.api.lifecycle;

/**
 * Hears the editor's application lifecycle. All callbacks run on the UI thread.
 */
public interface LifecycleListener {

    /** Every plugin registered; the main loop is about to start. */
    default void started() {
    }

    /** Background start-up work finished. */
    default void ready() {
    }

    /** Shutdown began; the GL context and UI still exist. */
    default void shuttingDown() {
    }
}
