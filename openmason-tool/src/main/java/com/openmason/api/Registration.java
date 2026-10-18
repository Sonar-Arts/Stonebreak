package com.openmason.api;

/**
 * Handle to one contribution; {@link #close()} removes it again. Closing twice is harmless.
 * Plugins rarely need it: a plugin's contributions are removed for it when its registration fails.
 */
@FunctionalInterface
public interface Registration extends AutoCloseable {

    /** A registration that removes nothing. */
    Registration NONE = () -> { };

    @Override
    void close();
}
