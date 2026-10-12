package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataState;
import com.openmason.engine.ui.data.Subscription;

import java.util.function.Consumer;

/** A member below a non-host feed ({@code .label} of a row item or of component parameters). */
record SubFeed(Feed parent, DataPath rel) implements Feed {

    @Override
    public DataState current() {
        return parent.current().at(rel);
    }

    @Override
    public Subscription watch(Consumer<DataState> listener) {
        DataState[] last = {current()};
        return parent.watch(s -> {
            DataState now = s.at(rel);
            if (!now.equals(last[0])) {
                last[0] = now;
                listener.accept(now);
            }
        });
    }

    @Override
    public DataPath hostPath() {
        DataPath base = parent.hostPath();
        return base == null ? null : base.resolve(rel);
    }

    @Override
    public Feed sub(DataPath more) {
        return more.isSelf() ? this : new SubFeed(parent, rel.resolve(more));
    }
}
