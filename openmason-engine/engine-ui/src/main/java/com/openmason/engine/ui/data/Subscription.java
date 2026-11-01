package com.openmason.engine.ui.data;

/** A registered listener; {@link #close()} releases it and is idempotent. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

    Subscription NONE = () -> {
    };

    @Override
    void close();
}
