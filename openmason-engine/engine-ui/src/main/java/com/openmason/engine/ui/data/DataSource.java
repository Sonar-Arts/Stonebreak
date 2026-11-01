package com.openmason.engine.ui.data;

/**
 * An observable snapshot the host exposes under a root name (#289). UI reads {@link #state()}
 * once and then reacts to notifications; nothing polls a source per frame. Sources are
 * UI-thread confined: producers on other threads go through {@link DataCell#post}.
 */
public interface DataSource {

    DataType type();

    DataState state();

    /** Registers {@code listener}; it is called after every change until the subscription closes. */
    Subscription subscribe(DataListener listener);

    /** Live listeners, for leak tests and diagnostics. */
    int subscriberCount();
}
