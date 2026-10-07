package com.openmason.engine.ui.diag;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.ui.script.UiScriptOptions;

/**
 * Runtime budgets of one open document (#283 version-one contract, enforced by #296).
 * Memory and the watchdog deadline are hard limits of the Lua state ({@link #scriptOptions});
 * script time per frame and layout time per invalidation are soft: going over is a
 * {@link UiBudgetTracker diagnostic}, never a failure.
 *
 * @param scriptFrameMillis Lua time per host frame (handlers, deliveries, {@code update(dt)})
 * @param memoryBytes       Lua heap cap of the screen's state
 * @param deadlineMillis    watchdog limit of any single call into Lua
 * @param layoutMillis      style + layout + placement time per invalidation for up to
 *                          {@code layoutNodes} elements; larger trees scale it linearly
 */
public record UiBudgets(Kind kind, double scriptFrameMillis, long memoryBytes, double deadlineMillis,
                        double layoutMillis, int layoutNodes) {

    public enum Kind { MENU, MINIGAME }

    /** Menus, HUDs, dialogs: 0.5 ms of script per frame, 4 MiB, 50 ms, 0.25 ms per relayout of ≤100 nodes. */
    public static final UiBudgets MENU = new UiBudgets(Kind.MENU, 0.5, 4L << 20, 50, 0.25, 100);

    /** Documents with a {@code Canvas} ({@code ui-canvas}): 2 ms of script per frame, 32 MiB. */
    public static final UiBudgets MINIGAME = new UiBudgets(Kind.MINIGAME, 2.0, 32L << 20, 50, 0.25, 100);

    public UiBudgets {
        if (kind == null || !(scriptFrameMillis > 0) || memoryBytes <= 0 || !(deadlineMillis > 0)
            || !(layoutMillis > 0) || layoutNodes <= 0) {
            throw new IllegalArgumentException("invalid UI budgets");
        }
    }

    /** {@link #MINIGAME} for a document that declares {@code ui-canvas}, else {@link #MENU}. */
    public static UiBudgets forDocument(OmuiArchive doc) {
        return doc.manifest().requires().contains(UiFeatures.CANVAS) ? MINIGAME : MENU;
    }

    /** The hard limits as Lua state options (no instruction budget, no graph tracing). */
    public UiScriptOptions scriptOptions() {
        return new UiScriptOptions(memoryBytes, deadlineMillis, 0, false);
    }

    /** Lua heap past which the soft budget warns: 80 % of the hard cap {@link #memoryBytes}. */
    public long memoryWarnBytes() {
        return memoryBytes / 5 * 4;
    }

    /** Allowed layout time for a tree of {@code nodes} elements. */
    public double layoutMillisFor(int nodes) {
        return layoutMillis * Math.max(1.0, (double) nodes / layoutNodes);
    }
}
