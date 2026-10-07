package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The standard host source (#289): one typed snapshot the game code replaces when the thing it
 * mirrors changes, so the UI is notified instead of polling. {@link #set} validates against the
 * schema (a mismatch is a host bug and throws) and notifies only when the value really changed.
 *
 * <p>UI-thread confined (checked once the host has drained on its UI thread). From another
 * thread use {@link #post}: posts coalesce, so a producer that changes every tick costs one UI
 * update per frame, not one per tick. A UI-thread {@link #setState} supersedes any post still
 * waiting for the drain, so an older cross-thread value never lands on top of a newer one.
 */
public final class DataCell implements DataSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataCell.class);

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
        UiThreadQueue q = queue;
        if (q != null) {
            q.checkOwner("DataCell.set");
        }
        check(next);
        pending.set(null); // a post still waiting for the drain is older than this value
        apply(next);
    }

    private void apply(DataState next) {
        if (next.equals(state)) {
            return;
        }
        state = next;
        listeners.fire(state, List.of());
    }

    /**
     * Thread-safe {@link #setState}: applied on the UI thread at the next drain of the queue
     * this cell was registered with. Only the latest posted state is applied, and a
     * {@link #setState} on the UI thread before the drain supersedes it.
     *
     * <p>The value is checked against the schema on the UI thread, not here: a producer (a
     * server tick, a network handler) never gets an exception for a host bug. A value that does
     * not match is logged and turns the cell {@link DataState.Failed failed}, so bound elements
     * show the failure instead of a stale value; the next valid post recovers.
     *
     * @throws IllegalStateException when the cell is not registered with a host
     */
    public void post(DataState next) {
        UiThreadQueue q = queue;
        if (q == null) {
            throw new IllegalStateException("post() needs a cell registered with a UiHost");
        }
        Objects.requireNonNull(next, "next");
        if (pending.getAndSet(next) == null) {
            q.post(() -> {
                DataState s = pending.getAndSet(null);
                if (s == null) {
                    return; // superseded by a UI-thread set
                }
                String problem = problem(s);
                if (problem != null) {
                    LOGGER.error("posted value rejected: {}", problem);
                    apply(DataState.failed("host posted an invalid value: " + problem));
                } else {
                    apply(s);
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
        String problem = problem(s);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return s;
    }

    private String problem(DataState s) {
        if (s instanceof DataState.Ready r) {
            String problem = type.problem(r.value());
            if (problem != null) {
                return "data cell of type " + type.describe() + ": " + problem;
            }
        }
        return null;
    }
}
