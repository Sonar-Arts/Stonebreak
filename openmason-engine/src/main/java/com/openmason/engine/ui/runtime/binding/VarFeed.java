package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataState;
import com.openmason.engine.ui.data.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A context the binder sets itself: live component parameters, or a row's current item. */
final class VarFeed implements Feed {

    private final List<Consumer<DataState>> listeners = new ArrayList<>();
    private DataState state;

    VarFeed(DataState initial) {
        this.state = initial;
    }

    @Override
    public DataState current() {
        return state;
    }

    void set(DataState next) {
        if (next.equals(state)) {
            return;
        }
        state = next;
        for (Consumer<DataState> l : listeners.toArray(Consumer[]::new)) {
            if (listeners.contains(l)) {
                l.accept(next);
            }
        }
    }

    @Override
    public Subscription watch(Consumer<DataState> listener) {
        listeners.add(listener);
        boolean[] closed = {false};
        return () -> {
            if (!closed[0]) {
                closed[0] = true;
                listeners.remove(listener);
            }
        };
    }

    int listenerCount() {
        return listeners.size();
    }

    @Override
    public DataPath hostPath() {
        return null;
    }
}
