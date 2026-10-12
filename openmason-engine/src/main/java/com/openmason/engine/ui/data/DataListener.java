package com.openmason.engine.ui.data;

import java.util.List;

/** Change notification from a {@link DataSource}; called on the UI thread. */
@FunctionalInterface
public interface DataListener {

    /**
     * @param state   the source's new state
     * @param changes incremental edits for a collection, empty when the whole value was replaced
     */
    void changed(DataState state, List<ListChange> changes);
}
