package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An observable list with item identity (#289). Edits notify listeners with the incremental
 * {@link ListChange}s, so a bound list view inserts, removes, moves or rebinds single rows;
 * {@link #setAll} diffs a fresh snapshot by identity and sends the same kind of changes.
 *
 * <p>UI-thread confined, like {@link DataCell}.
 */
public final class DataCollection implements DataSource {

    private final DataType.ListOf type;
    private final Listeners listeners = new Listeners();
    private final List<UiValue> items = new ArrayList<>();
    private DataState state;

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
        checkItem(item);
        if (indexOfKey(DataType.identityOf(item, type.identity())) >= 0) {
            throw new IllegalArgumentException("identity " + rawId(item) + " is already in the collection");
        }
        items.add(index, item);
        changed(List.of(new ListChange.Inserted(index, item)));
    }

    /** Replaces the item with the same identity. */
    public void update(UiValue item) {
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
        int i = indexOf(identityValue);
        if (i < 0) {
            return false;
        }
        UiValue gone = items.remove(i);
        changed(List.of(new ListChange.Removed(i, DataType.identityOf(gone, type.identity()))));
        return true;
    }

    public void move(int from, int to) {
        if (from != to) {
            items.add(to, items.remove(from));
            changed(List.of(new ListChange.Moved(from, to)));
        }
    }

    /** Replaces the contents, notifying the identity diff against the previous contents. */
    public void setAll(List<UiValue> next) {
        UiValue.Arr arr = new UiValue.Arr(next);
        String problem = type.problem(arr);
        if (problem != null) {
            throw new IllegalArgumentException("collection of type " + type.describe() + ": " + problem);
        }
        List<ListChange> changes = ListDiff.diff(items, next, type.identity());
        items.clear();
        items.addAll(next);
        if (!changes.isEmpty() || !state.isReady()) {
            changed(changes);
        }
    }

    public void loading() {
        state = DataState.LOADING;
        listeners.fire(state, List.of(ListChange.RESET));
    }

    public void fail(String message) {
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
