package com.openmason.engine.ui.data;

import java.util.Objects;

/**
 * A registered top-level data source (#289): the first segment of an absolute path
 * ({@code session} in {@code session.online}).
 *
 * @param edit how two-way bindings edit it, or {@code null} when it is read-only
 */
public record DataRoot(String name, DataSource source, HostContract contract, EditPolicy edit) {

    public DataRoot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(contract, "contract");
    }

    public boolean editable() {
        return edit != null;
    }
}
