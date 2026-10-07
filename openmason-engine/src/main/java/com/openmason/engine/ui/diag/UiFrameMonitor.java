package com.openmason.engine.ui.diag;

import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.openmason.engine.ui.script.UiScripts;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Live budget diagnostics for one {@link UiDocumentView} (#296). Attached as a view extension, it
 * samples once per host frame (in {@code view.frame(dt)}): the Lua wall time spent since the
 * previous frame (input handlers, deliveries, {@code update(dt)}) and the Lua heap; and it observes
 * every {@link UiDocumentInstance#update()} that ran Yoga. Numbers go to a
 * {@link UiBudgetTracker}; overruns go to a short recent list and an optional host listener
 * (logs), and the first one of each metric also becomes a {@code BUDGET_*} warning on the
 * instance ({@link UiRuntimeDiagnostic}).
 *
 * <p>Costs a few counters per frame, no allocation in steady state. The game shows
 * {@link #snapshot()} in the F3 overlay; the Open Mason preview next to its script stats.
 */
public final class UiFrameMonitor implements UiDocumentView.Extension, UiDocumentInstance.UpdateObserver {

    /** Frames in the rolling window: about two seconds at 60 fps. */
    public static final int WINDOW = 120;
    private static final int RECENT = 16;
    private static final int LOOKUP_EVERY = 60;

    private final UiDocumentView view;
    private final UiBudgetTracker tracker;
    private final ArrayDeque<UiBudgetTracker.Overrun> recent = new ArrayDeque<>(RECENT);
    private volatile Consumer<UiBudgetTracker.Overrun> listener;
    private UiScriptRuntime scripts;
    private long lastCallNanos;
    private int lookupIn;
    private boolean closed;
    private final boolean[] reported = new boolean[UiBudgetTracker.Metric.values().length];

    private UiFrameMonitor(UiDocumentView view, UiBudgets budgets) {
        this.view = view;
        this.tracker = new UiBudgetTracker(budgets, WINDOW, this::overrun);
    }

    /** Attaches a monitor with the document's own budgets ({@link UiBudgets#forDocument}). */
    public static UiFrameMonitor attach(UiDocumentView view) {
        return attach(view, UiBudgets.forDocument(view.instance().document()));
    }

    public static UiFrameMonitor attach(UiDocumentView view, UiBudgets budgets) {
        Objects.requireNonNull(view, "view");
        UiFrameMonitor existing = of(view);
        if (existing != null) {
            return existing;
        }
        UiFrameMonitor m = new UiFrameMonitor(view, Objects.requireNonNull(budgets, "budgets"));
        view.instance().addUpdateObserver(m);
        view.extend(m);
        return m;
    }

    /** The monitor attached to {@code view}, or null. */
    public static UiFrameMonitor of(UiDocumentView view) {
        for (UiDocumentView.Extension e : view.extensions()) {
            if (e instanceof UiFrameMonitor m) {
                return m;
            }
        }
        return null;
    }

    /** Receives each overrun as it is found (host logging); null clears. */
    public UiFrameMonitor onOverrun(Consumer<UiBudgetTracker.Overrun> l) {
        this.listener = l;
        return this;
    }

    public UiBudgets budgets() {
        return tracker.budgets();
    }

    /** True once its view closed; hosts drop closed monitors from their lists. */
    public boolean isClosed() {
        return closed;
    }

    public UiBudgetTracker.Snapshot snapshot() {
        return tracker.snapshot();
    }

    /** The last few overruns, oldest first. */
    public List<UiBudgetTracker.Overrun> recentOverruns() {
        return List.copyOf(recent);
    }

    @Override
    public void frame(double dt) {
        if (closed) {
            return;
        }
        UiScriptRuntime rt = runtime();
        long used = 0;
        long spent = 0;
        if (rt != null) {
            long calls = rt.callNanos();
            spent = calls - lastCallNanos;
            lastCallNanos = calls;
            used = rt.memoryUsed();
        }
        tracker.frame(spent, used);
    }

    @Override
    public void updated(UiDocumentInstance ui, UiDocumentInstance.UpdateStats stats) {
        if (stats.laidOut() && !closed) {
            tracker.layout(stats.nanos(), ui.elementCount());
        }
    }

    /**
     * The view's script runtime. Looked up again only now and then while absent (a document
     * without code-behind never gets one), and baselined when found, so the load and
     * {@code on_open} cost before the first frame is not charged to it.
     */
    private UiScriptRuntime runtime() {
        if (scripts != null && scripts.isClosed()) {
            scripts = null;
        }
        if (scripts == null && --lookupIn <= 0) {
            lookupIn = LOOKUP_EVERY;
            scripts = UiScripts.of(view);
            if (scripts != null) {
                lastCallNanos = scripts.callNanos();
            }
        }
        return scripts;
    }

    private void overrun(UiBudgetTracker.Overrun o) {
        if (recent.size() == RECENT) {
            recent.removeFirst();
        }
        recent.addLast(o);
        UiRuntimeDiagnostic.Code code = switch (o.metric()) {
            case SCRIPT_FRAME -> UiRuntimeDiagnostic.Code.BUDGET_SCRIPT_FRAME;
            case MEMORY -> UiRuntimeDiagnostic.Code.BUDGET_MEMORY;
            case LAYOUT -> UiRuntimeDiagnostic.Code.BUDGET_LAYOUT;
        };
        // The instance keeps diagnostics for the document's lifetime: one per metric is enough there;
        // every later overrun still reaches the recent list and the host listener.
        if (!reported[o.metric().ordinal()]) {
            reported[o.metric().ordinal()] = true;
            view.instance().reportDiagnostic(UiRuntimeDiagnostic.warning(code, "", o.message()));
        }
        Consumer<UiBudgetTracker.Overrun> l = listener;
        if (l != null) {
            l.accept(o);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            view.instance().removeUpdateObserver(this);
        }
    }
}
