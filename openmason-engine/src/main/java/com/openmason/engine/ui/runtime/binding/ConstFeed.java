package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataState;
import com.openmason.engine.ui.data.Subscription;

import java.util.function.Consumer;

/** A context that never changes (no inherited source: every relative path is missing). */
record ConstFeed(DataState state) implements Feed {

    static final ConstFeed NONE = new ConstFeed(DataState.MISSING);

    @Override
    public DataState current() {
        return state;
    }

    @Override
    public Subscription watch(Consumer<DataState> listener) {
        return Subscription.NONE;
    }

    @Override
    public DataPath hostPath() {
        return null;
    }
}
