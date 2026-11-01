package com.openmason.engine.ui.data;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The typed paths a host exposes (#289): one {@link DataRoot} per root name. Bindings, scripts
 * and graphs can only reach what is registered here; there is no reflective access to game
 * objects.
 */
public final class DataRegistry {

    /** A data path segment: the root is the first one ({@code session} in {@code session.online}). */
    private static final java.util.regex.Pattern ROOT_NAME = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Map<String, DataRoot> roots = new LinkedHashMap<>();
    private final UiThreadQueue queue;
    private final Runnable onChange;

    DataRegistry(UiThreadQueue queue, Runnable onChange) {
        this.queue = queue;
        this.onChange = onChange;
    }

    public <S extends DataSource> S register(String root, S source, HostContract contract) {
        add(new DataRoot(root, source, contract, null));
        return source;
    }

    /** A root two-way bindings may edit through drafts committed by {@code policy}'s action. */
    public DataCell registerEditable(String root, DataCell cell, HostContract contract, EditPolicy policy) {
        add(new DataRoot(root, cell, contract, java.util.Objects.requireNonNull(policy, "policy")));
        return cell;
    }

    private void add(DataRoot r) {
        if (!ROOT_NAME.matcher(r.name()).matches()) {
            throw new IllegalArgumentException("data root must be an identifier: " + r.name());
        }
        if (roots.putIfAbsent(r.name(), r) != null) {
            throw new IllegalArgumentException("data root " + r.name() + " is already registered");
        }
        if (r.source() instanceof DataCell cell) {
            cell.attach(queue);
        } else if (r.source() instanceof DataCollection col) {
            col.attach(queue);
        }
        onChange.run();
    }

    /** @return the root, or {@code null} when the host has none by that name */
    public DataRoot root(String name) {
        return roots.get(name);
    }

    public Collection<DataRoot> roots() {
        return Collections.unmodifiableCollection(roots.values());
    }
}
