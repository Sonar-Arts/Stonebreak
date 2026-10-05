package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataState;
import com.openmason.engine.ui.data.Subscription;

import java.util.function.Consumer;

/**
 * A data context an element inherits (#289): a host path, a component's parameters, a
 * collection row's item, or a member below one of those. Bindings evaluate their relative paths
 * against the nearest feed, so swapping a row's item (recycling) rebinds its whole subtree.
 */
interface Feed {

    DataState current();

    /** Notifies {@code listener} of every later change of {@link #current()}. */
    Subscription watch(Consumer<DataState> listener);

    /** The host path this feed reads, or {@code null} when it is not host data (not writable). */
    DataPath hostPath();

    default Feed sub(DataPath rel) {
        return rel.isSelf() ? this : new SubFeed(this, rel);
    }
}
