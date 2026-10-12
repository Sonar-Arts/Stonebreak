package com.openmason.engine.ui.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Listener list shared by the sources: snapshot iteration (a listener may unsubscribe itself
 * or others while being notified), and one failing listener never starves the rest.
 */
final class Listeners {

    private static final Logger LOGGER = LoggerFactory.getLogger(Listeners.class);

    private final List<DataListener> list = new ArrayList<>();

    Subscription add(DataListener l) {
        list.add(l);
        boolean[] closed = {false};
        return () -> {
            if (!closed[0]) {
                closed[0] = true;
                list.remove(l);
            }
        };
    }

    int size() {
        return list.size();
    }

    void fire(DataState state, List<ListChange> changes) {
        for (DataListener l : list.toArray(DataListener[]::new)) {
            if (!list.contains(l)) {
                continue; // unsubscribed by an earlier listener in this round
            }
            try {
                l.changed(state, changes);
            } catch (RuntimeException e) {
                LOGGER.error("UI data listener failed", e);
            }
        }
    }
}
