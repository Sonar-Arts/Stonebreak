package com.stonebreak.ui.debug;

import com.openmason.engine.ui.diag.UiBudgetTracker;
import com.openmason.engine.ui.masonry.MStatPanel;
import com.stonebreak.ui.runtime.GameUiDiagnostics;

import java.util.List;
import java.util.Locale;

/**
 * The F3 "UI Documents" card (#296): per monitored document, Lua time per frame, Lua heap and
 * relayout time against the document's budgets, with a {@code !} on any metric that is over.
 * Shown only while a document is monitored.
 */
public final class UiBudgetPanel implements DebugPanel {

    public boolean hasContent() {
        return !GameUiDiagnostics.live().isEmpty();
    }

    @Override
    public MStatPanel build() {
        MStatPanel panel = new MStatPanel("UI Documents");
        List<GameUiDiagnostics.Entry> live = GameUiDiagnostics.live();
        if (live.isEmpty()) {
            panel.row("(none)", "");
            return panel;
        }
        for (GameUiDiagnostics.Entry e : live) {
            UiBudgetTracker.Snapshot s = e.monitor().snapshot();
            panel.section(shortName(e.name()) + " (" + s.budgets().kind().name().toLowerCase(Locale.ROOT) + ")");
            panel.row(mark(s, UiBudgetTracker.Metric.SCRIPT_FRAME) + "Lua / frame",
                String.format(Locale.ROOT, "%.3f / %.2f ms", s.scriptMeanMillis(), s.budgets().scriptFrameMillis()));
            panel.row(mark(s, UiBudgetTracker.Metric.SCRIPT_SPIKE) + "Lua frame max",
                String.format(Locale.ROOT, "%.3f / %.2f ms", s.scriptMaxMillis(), s.budgets().scriptSpikeMillis()));
            panel.row(mark(s, UiBudgetTracker.Metric.MEMORY) + "Lua heap",
                DebugFormat.formatBytes(s.memoryBytes()) + " / " + DebugFormat.formatBytes(s.budgets().memoryBytes()));
            panel.row(mark(s, UiBudgetTracker.Metric.LAYOUT) + "Relayout max",
                String.format(Locale.ROOT, "%.3f / %.2f ms", s.layoutMaxMillis(), s.budgets().layoutMillisFor(s.nodes())));
            if (s.overruns() > 0) {
                panel.row("Overruns", Long.toString(s.overruns()));
            }
        }
        return panel;
    }

    private static String mark(UiBudgetTracker.Snapshot s, UiBudgetTracker.Metric m) {
        return s.over().contains(m) ? "! " : "";
    }

    private static String shortName(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
