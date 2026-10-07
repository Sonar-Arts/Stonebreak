package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataState;
import com.openmason.engine.ui.data.Subscription;
import com.openmason.engine.ui.data.UiScope;

import java.util.function.Consumer;

/** An absolute host path, read and watched through the document's {@link UiScope}. */
record HostFeed(UiScope scope, DataPath path) implements Feed {

    @Override
    public DataState current() {
        return scope.read(path);
    }

    @Override
    public Subscription watch(Consumer<DataState> listener) {
        return scope.watch(path, listener);
    }

    @Override
    public DataPath hostPath() {
        return path;
    }

    @Override
    public Feed sub(DataPath rel) {
        return rel.isSelf() ? this : new HostFeed(scope, path.resolve(rel));
    }
}
