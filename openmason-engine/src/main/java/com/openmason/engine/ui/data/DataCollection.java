package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An observable list with item identity (#289). Edits notify listeners with the incremental
 * {@link ListChange}s, so a bound list view inserts, removes, moves or rebinds single rows;
 * {@link #setAll} diffs a fresh snapshot by identity and sends the same kind of changes.
 *
 * <p>UI-thread confined, like {@link DataCell}; from another thread {@link #post} a whole new
 * snapshot (posts coalesce and are diffed by identity at the next drain).
 */
public final class DataCollection implements DataSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataCollection.class);

    private final DataType.ListOf type;
    private final Listeners listeners = new Listeners();
    private final List<UiValue> items = new ArrayList<>();
    private final AtomicReference<List<UiValue>> pending = new AtomicReference<>();
    private DataState state;
    private UiThreadQueue queue;

    /** @param type a list type with an identity field */
    public DataCollection(DataType.ListOf type) {
        this.type = Objects.requireNonNull(type, "type");
        if (type.identity() == null) {
            throw new IllegalArgumentException("a collection needs an identity field");
        }
        this.state = DataState.ready(new UiValue.Arr(List.of()));
    }

    @Override
    public DataType.ListOf type() {
        return type;
    }

    @Override
    public DataState state() {
        return state;
    }

    public List<UiValue> items() {
        return Collections.unmodifiableList(items);
    }

    public int indexOf(String identityValue) {
        for (int i = 0; i < items.size(); i++) {
            if (identityValue.equals(rawId(items.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    public void add(UiValue item) {
        insert(items.size(), item);
    }

    public void insert(int index, UiValue item) {
        owner();
        checkItem(item);
        if (indexOfKey(DataType.identityOf(item, type.identity())) >= 0) {
            throw new IllegalArgumentException("identity " + rawId(item) + " is already in the collection");
        }
        items.add(index, item);
        changed(List.of(new ListChange.Inserted(index, item)));
    }

    /** Replaces the item with the same identity. */
    public void update(UiValue item) {
        owner();
        checkItem(item);
        int i = indexOfKey(DataType.identityOf(item, type.identity()));
        if (i < 0) {
            throw new IllegalArgumentException("no item with identity " + rawId(item));
        }
        if (!items.get(i).equals(item)) {
            items.set(i, item);
            changed(List.of(new ListChange.Updated(i, item)));
        }
    }

    /** @return false when no item has that identity */
    public boolean remove(String identityValue) {
        owner();
        int i = indexOf(identityValue);
        if (i < 0) {
            return false;
        }
        UiValue gone = items.remove(i);
        changed(List.of(new ListChange.Removed(i, DataType.identityOf(gone, type.identity()))));
        return true;
    }

    public void move(int from, int to) {
        owner();
        if (from != to) {
            items.add(to, items.remove(from));
            changed(List.of(new ListChange.Moved(from, to)));
        }
    }

    /**
     * Replaces the contents, notifying the identity diff against the previous contents. Any
     * {@link #post} still waiting for the drain is superseded.
     */
    public void setAll(List<UiValue> next) {
        owner();
        String problem = problem(next);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        pending.set(null);
        replace(next);
    }

    /**
     * Thread-safe {@link #setAll}: the snapshot is copied now and applied on the UI thread at the
     * next drain of the host it is registered with, as the same identity diff. Only the latest
     * posted snapshot is applied. Like {@link DataCell#post}, an invalid snapshot is reported on
     * the UI thread (logged, collection failed) instead of throwing into the producer.
     *
     * @throws IllegalStateException when the collection is not registered with a host
     */
    public void post(List<UiValue> next) {
        UiThreadQueue q = queue;
        if (q == null) {
            throw new IllegalStateException("post() needs a collection registered with a UiHost");
        }
        List<UiValue> snapshot = List.copyOf(next);
        if (pending.getAndSet(snapshot) == null) {
            q.post(() -> {
                List<UiValue> s = pending.getAndSet(null);
                if (s == null) {
                    return; // superseded by a UI-thread edit
                }
                String problem = problem(s);
                if (problem != null) {
                    LOGGER.error("posted collection rejected: {}", problem);
                    state = DataState.failed("host posted an invalid list: " + problem);
                    listeners.fire(state, List.of(ListChange.RESET));
                } else {
                    replace(s);
                }
            });
        }
    }

    private String problem(List<UiValue> next) {
        String problem = type.problem(new UiValue.Arr(next));
        return problem == null ? null : "collection of type " + type.describe() + ": " + problem;
    }

    private void replace(List<UiValue> next) {
        List<ListChange> changes = ListDiff.diff(items, next, type.identity());
        items.clear();
        items.addAll(next);
        if (!changes.isEmpty() || !state.isReady()) {
            changed(changes);
        }
    }

    public void loading() {
        owner();
        state = DataState.LOADING;
        listeners.fire(state, List.of(ListChange.RESET));
    }

    public void fail(String message) {
        owner();
        state = DataState.failed(message);
        listeners.fire(state, List.of(ListChange.RESET));
    }

    @Override
    public Subscription subscribe(DataListener listener) {
        return listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public int subscriberCount() {
        return listeners.size();
    }

    void attach(UiThreadQueue q) {
        this.queue = q;
    }

    /** UI-thread edits supersede a post still waiting for the drain. */
    private void owner() {
        UiThreadQueue q = queue;
        if (q != null) {
            q.checkOwner("DataCollection edit");
        }
        pending.set(null);
    }

    private void changed(List<ListChange> changes) {
        boolean wasReady = state.isReady();
        state = DataState.ready(new UiValue.Arr(items));
        listeners.fire(state, wasReady ? changes : List.of(ListChange.RESET));
    }

    private int indexOfKey(String key) {
        for (int i = 0; i < items.size(); i++) {
            if (Objects.equals(key, DataType.identityOf(items.get(i), type.identity()))) {
                return i;
            }
        }
        return -1;
    }

    private String rawId(UiValue item) {
        UiValue v = item instanceof UiValue.Obj o ? o.get(type.identity()) : null;
        return switch (v) {
            case UiValue.Str s -> s.value();
            case UiValue.Num n -> n.isIntegral() ? Long.toString((long) n.value()) : Double.toString(n.value());
            case null, default -> String.valueOf(v);
        };
    }

    private void checkItem(UiValue item) {
        String problem = type.item().problem(item);
        if (problem == null && DataType.identityOf(item, type.identity()) == null) {
            problem = "item has no identity field '" + type.identity() + "'";
        }
        if (problem != null) {
            throw new IllegalArgumentException("collection item: " + problem);
        }
    }
}
