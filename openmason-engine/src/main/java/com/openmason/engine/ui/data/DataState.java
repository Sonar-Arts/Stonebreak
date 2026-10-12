package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;

import java.util.Objects;

/**
 * What a data source currently offers (#289). A binding shows {@link Ready} values; for every
 * other state it falls back to the target's authored or default value, so a screen never
 * paints a half-loaded or broken value as if it were real.
 */
public sealed interface DataState {

    DataState LOADING = new Loading();
    DataState MISSING = new Missing();

    static DataState ready(UiValue value) {
        return new Ready(value);
    }

    static DataState failed(String message) {
        return new Failed(message);
    }

    default boolean isReady() {
        return this instanceof Ready;
    }

    /** The value when ready, else {@code null}. */
    default UiValue valueOrNull() {
        return this instanceof Ready r ? r.value() : null;
    }

    /** The state at {@code path} below this one: a missing member is {@link #MISSING}. */
    default DataState at(DataPath path) {
        if (path.isSelf() || !(this instanceof Ready r)) {
            return this;
        }
        UiValue v = path.evaluate(r.value());
        return v == null ? MISSING : ready(v);
    }

    /** A value; {@link UiValue#NULL} is a ready null (the source exists and says "nothing"). */
    record Ready(UiValue value) implements DataState {
        public Ready {
            Objects.requireNonNull(value, "value");
        }
    }

    /** Requested, not there yet. */
    record Loading() implements DataState {
    }

    /** No such source or member (an unloaded world, a field the schema has but the snapshot lacks). */
    record Missing() implements DataState {
    }

    record Failed(String message) implements DataState {
        public Failed {
            Objects.requireNonNull(message, "message");
        }
    }
}
