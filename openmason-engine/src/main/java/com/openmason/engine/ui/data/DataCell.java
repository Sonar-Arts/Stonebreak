package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The standard host source (#289): one typed snapshot the game code replaces when the thing it
 * mirrors changes, so the UI is notified instead of polling. {@link #set} validates against the
 * schema (a mismatch is a host bug and throws) and notifies only when the value really changed.
 *
 * <p>UI-thread confined. From another thread use {@link #post}: posts coalesce, so a producer
 * that changes every tick costs one UI update per frame, not one per tick.
 */
public final class DataCell implements DataSource {

    private final DataType type;
    private final Listeners listeners = new Listeners();
    private final AtomicReference<DataState> pending = new AtomicReference<>();
    private DataState state;
    private UiThreadQueue queue;

    public DataCell(DataType type, UiValue initial) {
        this(type, DataState.ready(initial));
    }

    public DataCell(DataType type, DataState initial) {
        this.type = Objects.requireNonNull(type, "type");
        this.state = check(Objects.requireNonNull(initial, "initial"));
    }

    @Override
    public DataType type() {
        return type;
    }

    @Override
    public DataState state() {
        return state;
    }

    /** The current value, or {@code null} unless ready. */
    public UiValue value() {
        return state.valueOrNull();
    }

    public void set(UiValue value) {
        setState(DataState.ready(value));
    }

    /** Replaces the member at an absolute or relative {@code path} below the root. */
    public void set(DataPath path, UiValue value) {
        UiValue base = state.valueOrNull();
        set(path.with(base == null ? UiValue.Obj.EMPTY : base, value));
    }

    public void loading() {
        setState(DataState.LOADING);
    }

    public void missing() {
        setState(DataState.MISSING);
    }

    public void fail(String message) {
        setState(DataState.failed(message));
    }

    public void setState(DataState next) {
        check(next);
        if (next.equals(state)) {
            return;
        }
        state = next;
        listeners.fire(state, List.of());
    }

    /**
     * Thread-safe {@link #setState}: applied on the UI thread at the next drain of the queue
     * this cell was registered with. Only the latest posted state is applied.
     *
     * @throws IllegalStateException when the cell is not registered with a host
     */
    public void post(DataState next) {
        UiThreadQueue q = queue;
        if (q == null) {
            throw new IllegalStateException("post() needs a cell registered with a UiHost");
        }
        check(next);
        if (pending.getAndSet(next) == null) {
            q.post(() -> {
                DataState s = pending.getAndSet(null);
                if (s != null) {
                    setState(s);
                }
            });
        }
    }

    public void post(UiValue value) {
        post(DataState.ready(value));
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

    private DataState check(DataState s) {
        if (s instanceof DataState.Ready r) {
            String problem = type.problem(r.value());
            if (problem != null) {
                throw new IllegalArgumentException("data cell of type " + type.describe() + ": " + problem);
            }
        }
        return s;
    }
}
