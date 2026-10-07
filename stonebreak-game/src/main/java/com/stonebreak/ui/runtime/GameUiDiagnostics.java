package com.stonebreak.ui.runtime;

import com.openmason.engine.ui.diag.UiBudgetTracker;
import com.openmason.engine.ui.diag.UiFrameMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The game's list of monitored UI documents (#296): what the F3 overlay's "UI Documents" card
 * shows, and where overruns are logged ({@code [ui-budget]} warnings). Monitors close with their
 * views and drop off the list on the next read. Render-thread only.
 */
public final class GameUiDiagnostics {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameUiDiagnostics.class);

    /** One monitored document. */
    public record Entry(String name, UiFrameMonitor monitor) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private GameUiDiagnostics() {
    }

    public static void register(String name, UiFrameMonitor monitor) {
        prune();
        for (Entry e : ENTRIES) {
            if (e.monitor() == monitor) {
                return;
            }
        }
        monitor.onOverrun(o -> log(name, o));
        ENTRIES.add(new Entry(name, monitor));
    }

    /** Live monitored documents, oldest first. */
    public static List<Entry> live() {
        prune();
        return List.copyOf(ENTRIES);
    }

    private static void prune() {
        ENTRIES.removeIf(e -> e.monitor().isClosed());
    }

    private static void log(String name, UiBudgetTracker.Overrun o) {
        LOGGER.warn("[ui-budget] {}: {}", name, o.message());
    }
}
