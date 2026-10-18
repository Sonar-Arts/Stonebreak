package com.openmason.api;

import java.util.List;
import java.util.Optional;

/**
 * A plugin's view of one {@link Contributions} list: what it registers is owned by that plugin
 * (and removed with it when its registration fails); it can read everything registered so far.
 *
 * @param <T> the contribution type
 */
public interface Registrar<T> {

    /**
     * Adds {@code item}.
     *
     * @throws IllegalArgumentException when an item with the same id is already registered
     */
    Registration register(T item);

    /** Everything registered so far (by every plugin), in registration order. */
    List<T> all();

    /** The item with {@code id}, if registered. */
    Optional<T> find(String id);
}
