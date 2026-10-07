package com.openmason.engine.ui.diag;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Soft-budget bookkeeping for one document (#296): fed raw per-frame and per-layout measurements,
 * it keeps rolling statistics and reports an {@link Overrun} when a metric goes over its
 * {@link UiBudgets budget}. Pure and clock-free (callers pass measured nanoseconds), so the rules
 * are unit-tested with synthetic numbers; {@link UiFrameMonitor} feeds it from a live view.
 *
 * <p>Rules, chosen so one GC pause or JIT warm-up never cries wolf:
 * <ul>
 *   <li><b>Script time</b> is over when the mean of the last {@code window} frames exceeds the
 *       budget (judged once 30 frames are in), and clears below 80 % of it.</li>
 *   <li><b>Script spikes</b>: a mean hides a handler that stalls one frame in twenty. A frame
 *       spikes when its Lua time exceeds {@link UiBudgets#scriptSpikeMillis}; the metric is over
 *       when 3 frames of the window spiked and clears when none has (judged once 30 frames are
 *       in, like the mean).</li>
 *   <li><b>Memory</b> is over above {@link UiBudgets#memoryWarnBytes} (80 % of the cap, which is
 *       also the Lua state's hard limit, so the warning comes before the out-of-memory error) and
 *       clears below 90 % of that.</li>
 *   <li><b>Layout</b> is measured per invalidation (an update that ran Yoga) against
 *       {@link UiBudgets#layoutMillisFor} the tree's size, and is over when 3 of the last 16
 *       invalidations exceeded it (a lone GC or JIT spike is not a trend); it clears when at most
 *       one does, so a load hovering at the threshold does not flap. The first layout of a
 *       document and the first after each {@link #coldStart() reload} (cold: tree creation,
 *       first text measurement) are recorded but not judged.</li>
 * </ul>
 * Each metric reports on its rising edge, at most once per {@code window} frames.
 */
public final class UiBudgetTracker {

    public enum Metric { SCRIPT_FRAME, SCRIPT_SPIKE, MEMORY, LAYOUT }

    /** One budget going over: the measured value and the budget, both in the metric's unit. */
    public record Overrun(Metric metric, double value, double budget, String message) {
    }

    /**
     * Current statistics; times in milliseconds, script figures over the rolling window.
     *
     * @param frames   frames observed so far
     * @param layouts  warm invalidations observed so far (the cold first layout excluded)
     * @param over     metrics currently over budget
     */
    public record Snapshot(UiBudgets budgets, long frames, double scriptLastMillis, double scriptMeanMillis,
                           double scriptMaxMillis, long memoryBytes, long memoryPeakBytes, long layouts,
                           double layoutLastMillis, double layoutMaxMillis, double coldLayoutMillis,
                           int nodes, long overruns, Set<Metric> over) {

        /** Human-readable lines (ASCII only: Open Mason's ImGui font has no typographic glyphs). */
        public List<String> lines() {
            List<String> out = new ArrayList<>(3);
            out.add(String.format(Locale.ROOT, "%sscript %.3f ms/frame (avg %.3f, max %.3f) of %.2f",
                over.contains(Metric.SCRIPT_SPIKE) ? "! " : mark(Metric.SCRIPT_FRAME), scriptLastMillis, scriptMeanMillis, scriptMaxMillis,
                budgets.scriptFrameMillis()));
            out.add(String.format(Locale.ROOT, "%slua heap %s (peak %s) of %s", mark(Metric.MEMORY),
                mib(memoryBytes), mib(memoryPeakBytes), mib(budgets.memoryBytes())));
            out.add(String.format(Locale.ROOT, "%slayout %.3f ms (max %.3f, cold %.3f, %d nodes, %d inv) of %.2f",
                mark(Metric.LAYOUT), layoutLastMillis, layoutMaxMillis, coldLayoutMillis, nodes, layouts,
                budgets.layoutMillisFor(nodes)));
            return out;
        }

        private String mark(Metric m) {
            return over.contains(m) ? "! " : "  ";
        }

        private static String mib(long bytes) {
            return String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0));
        }
    }

    private static final int MIN_FRAMES = 30;
    private static final int LAYOUT_WINDOW = 16;
    private static final int LAYOUT_STRIKES = 3;
    private static final int SPIKE_STRIKES = 3;

    private final UiBudgets budgets;
    private final Consumer<Overrun> sink;
    private final int window;
    private final long[] script;
    private final boolean[] spiked;
    private int spikes;
    private int count;
    private int pos;
    private long sum;
    private long frames;
    private long scriptLast;
    private long memory;
    private long memoryPeak;
    private long layouts;
    private boolean cold = true;
    private long layoutLast;
    private long layoutMax;
    private long coldLayout;
    private int nodes;
    private long overruns;
    private final boolean[] layoutOver = new boolean[LAYOUT_WINDOW];
    private int layoutPos;
    private int layoutStrikes;
    private final boolean[] over = new boolean[Metric.values().length];
    private final long[] lastReport = new long[Metric.values().length];

    /**
     * @param window frames in the rolling script-time window (and the report cool-down)
     * @param sink   receives each overrun as it happens
     */
    public UiBudgetTracker(UiBudgets budgets, int window, Consumer<Overrun> sink) {
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.sink = Objects.requireNonNull(sink, "sink");
        if (window < MIN_FRAMES) {
            throw new IllegalArgumentException("window must be at least " + MIN_FRAMES + " frames");
        }
        this.window = window;
        this.script = new long[window];
        this.spiked = new boolean[window];
        java.util.Arrays.fill(lastReport, Long.MIN_VALUE / 2);
    }

    public UiBudgets budgets() {
        return budgets;
    }

    /** One host frame: Lua wall time spent since the previous frame and the Lua heap in use now. */
    public void frame(long scriptNanos, long memoryBytes) {
        frames++;
        scriptLast = scriptNanos;
        sum += scriptNanos - (count == window ? script[pos] : 0);
        script[pos] = scriptNanos;
        boolean spike = scriptNanos / 1e6 > budgets.scriptSpikeMillis();
        spikes += (spike ? 1 : 0) - (count == window && spiked[pos] ? 1 : 0);
        spiked[pos] = spike;
        pos = (pos + 1) % window;
        count = Math.min(count + 1, window);
        memory = memoryBytes;
        memoryPeak = Math.max(memoryPeak, memoryBytes);

        if (count >= MIN_FRAMES) {
            double mean = sum / (double) count / 1e6;
            double budget = budgets.scriptFrameMillis();
            judge(Metric.SCRIPT_FRAME, mean > budget, mean < budget * 0.8, mean, budget,
                "Lua took %.3f ms per frame on average over %d frames; the budget is %.2f ms", count);
        }
        if (count >= MIN_FRAMES) { // the first frames carry JIT warm-up
            judge(Metric.SCRIPT_SPIKE, spikes >= SPIKE_STRIKES, spikes == 0, scriptNanos / 1e6,
                budgets.scriptSpikeMillis(),
                "Lua took %.3f ms in one frame (%d frames of the window over); the spike limit is %.2f ms", spikes);
        }
        double used = memoryBytes;
        double warn = budgets.memoryWarnBytes();
        judge(Metric.MEMORY, used > warn, used < warn * 0.9, used, budgets.memoryBytes(),
            "Lua heap is %.0f bytes, past 80 %% of its %.0f byte cap", 0);
    }

    /**
     * The next layout is cold again (the document was reloaded and its tree rebuilt): it is
     * recorded as the cold figure and not judged against the warm budget.
     */
    public void coldStart() {
        cold = true;
    }

    /** One {@code update()} that ran Yoga, with the tree's element count. */
    public void layout(long nanos, int elementCount) {
        nodes = elementCount;
        if (cold) {
            cold = false;
            coldLayout = nanos;
            return;
        }
        layouts++;
        layoutLast = nanos;
        layoutMax = Math.max(layoutMax, nanos);
        double ms = nanos / 1e6;
        double budget = budgets.layoutMillisFor(elementCount);
        boolean isOver = ms > budget;
        layoutStrikes += (isOver ? 1 : 0) - (layoutOver[layoutPos] ? 1 : 0);
        layoutOver[layoutPos] = isOver;
        layoutPos = (layoutPos + 1) % LAYOUT_WINDOW;
        judge(Metric.LAYOUT, layoutStrikes >= LAYOUT_STRIKES, layoutStrikes <= 1, ms, budget,
            "relayout took %.3f ms for %d elements (3+ of the last 16 over); the budget is %.2f ms", elementCount);
    }

    private void judge(Metric m, boolean isOver, boolean isClear, double value, double budget, String format,
                       int detail) {
        int i = m.ordinal();
        if (!over[i] && isOver) {
            over[i] = true;
            overruns++;
            if (frames - lastReport[i] >= window) {
                lastReport[i] = frames;
                String msg = m == Metric.MEMORY
                    ? String.format(Locale.ROOT, format, value, budget)
                    : String.format(Locale.ROOT, format, value, detail, budget);
                sink.accept(new Overrun(m, value, budget, msg));
            }
        } else if (over[i] && isClear) {
            over[i] = false;
        }
    }

    public Snapshot snapshot() {
        long max = 0;
        for (int k = 0; k < count; k++) {
            max = Math.max(max, script[k]);
        }
        EnumSet<Metric> now = EnumSet.noneOf(Metric.class);
        for (Metric m : Metric.values()) {
            if (over[m.ordinal()]) {
                now.add(m);
            }
        }
        return new Snapshot(budgets, frames, scriptLast / 1e6, count == 0 ? 0 : sum / (double) count / 1e6,
            max / 1e6, memory, memoryPeak, layouts, layoutLast / 1e6, layoutMax / 1e6, coldLayout / 1e6, nodes,
            overruns, Set.copyOf(now));
    }
}
