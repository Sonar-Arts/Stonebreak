package com.openmason.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * One kind of contribution (workspaces, file types, menu items, ...): an ordered list of items,
 * each owned by the plugin that registered it and unique by id.
 *
 * <p>Order is registration order, so the built-in plugins (registered first) keep the order the
 * editor always had, and a plugin's items follow the plugins it requires. Readers take
 * {@link #all()} snapshots; listeners hear about every add and remove (e.g. a workspace appearing
 * after start-up).
 *
 * <p>Not thread-safe for writers: register from the UI thread (where {@link OpenMasonPlugin#register}
 * runs). {@link #all()} and {@link #find} may be read from any thread.
 *
 * @param <T> the contribution type
 */
public final class Contributions<T> {

    /** Hears additions and removals. */
    public interface Listener<T> {
        void added(T item);

        default void removed(T item) {
        }
    }

    private record Entry<T>(String owner, String id, T item) {
    }

    private final String kind;
    private final Function<? super T, String> idOf;
    private final List<Entry<T>> entries = new CopyOnWriteArrayList<>();
    private final List<Listener<T>> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param kind what is contributed, for messages ("workspace", "file type")
     * @param idOf the unique id of an item
     */
    public Contributions(String kind, Function<? super T, String> idOf) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.idOf = Objects.requireNonNull(idOf, "idOf");
    }

    /** What is contributed ("workspace", "file type"...). */
    public String kind() {
        return kind;
    }

    /**
     * Adds {@code item} on behalf of plugin {@code owner}.
     *
     * @throws IllegalArgumentException when an item with the same id is already registered
     */
    public Registration register(String owner, T item) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(item, "item");
        String id = Objects.requireNonNull(idOf.apply(item), () -> kind + " id");
        for (Entry<T> e : entries) {
            if (e.id().equals(id)) {
                throw new IllegalArgumentException("The " + kind + " '" + id + "' is already registered by "
                        + e.owner());
            }
        }
        Entry<T> entry = new Entry<>(owner, id, item);
        entries.add(entry);
        listeners.forEach(l -> l.added(item));
        return () -> remove(entry);
    }

    /** Every item, in registration order. */
    public List<T> all() {
        List<T> out = new ArrayList<>(entries.size());
        for (Entry<T> e : entries) {
            out.add(e.item());
        }
        return List.copyOf(out);
    }

    /** The item with {@code id}, if registered. */
    public Optional<T> find(String id) {
        if (id != null) {
            for (Entry<T> e : entries) {
                if (e.id().equals(id)) {
                    return Optional.of(e.item());
                }
            }
        }
        return Optional.empty();
    }

    /** The plugin that registered {@code id}, if registered. */
    public Optional<String> ownerOf(String id) {
        for (Entry<T> e : entries) {
            if (e.id().equals(id)) {
                return Optional.of(e.owner());
            }
        }
        return Optional.empty();
    }

    /** The items {@code owner} registered, in order. */
    public List<T> ownedBy(String owner) {
        List<T> out = new ArrayList<>();
        for (Entry<T> e : entries) {
            if (e.owner().equals(owner)) {
                out.add(e.item());
            }
        }
        return List.copyOf(out);
    }

    /** Removes everything {@code owner} registered (a failed plugin's rollback). */
    public void removeOwner(String owner) {
        for (Entry<T> e : List.copyOf(entries)) {
            if (e.owner().equals(owner)) {
                remove(e);
            }
        }
    }

    public int size() {
        return entries.size();
    }

    public void addListener(Listener<T> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** A registrar that registers on behalf of {@code owner}. */
    public Registrar<T> registrar(String owner) {
        Objects.requireNonNull(owner, "owner");
        return new Registrar<>() {
            @Override
            public Registration register(T item) {
                return Contributions.this.register(owner, item);
            }

            @Override
            public List<T> all() {
                return Contributions.this.all();
            }

            @Override
            public Optional<T> find(String id) {
                return Contributions.this.find(id);
            }
        };
    }

    private void remove(Entry<T> entry) {
        if (entries.remove(entry)) {
            listeners.forEach(l -> l.removed(entry.item()));
        }
    }
}
