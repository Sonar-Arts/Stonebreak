package com.stonebreak.ui.debug;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.vram.VramPlan;
import com.openmason.engine.vram.VramPlans;
import com.stonebreak.rendering.UI.masonryUI.MStatPanel;

import static com.stonebreak.ui.debug.DebugFormat.formatBytes;
import static com.stonebreak.ui.debug.DebugFormat.shortCategoryName;

/**
 * The left-hand "VRAM (Game)" card: per-process GPU memory from
 * {@link GpuMemoryTracker} by category, the active CEARL plan's pressure, and
 * the system-wide reading from {@link GpuInfoProbe}.
 */
public final class VramPanel implements DebugPanel {

    private final GpuInfoProbe gpu;

    public VramPanel(GpuInfoProbe gpu) {
        this.gpu = gpu;
    }

    /**
     * Builds the VRAM card from {@link GpuMemoryTracker}. The bar communicates
     * "fraction of the GPU's dedicated VRAM that this process owns" when the
     * NV/ATI extension is available.
     */
    @Override
    public MStatPanel build() {
        GpuMemoryTracker.Snapshot snap = GpuMemoryTracker.getInstance().snapshot();
        long trackedTotal = snap.totalBytes();
        long systemTotalBytes = gpu.systemTotalBytes();

        MStatPanel panel = new MStatPanel("VRAM (Game)")
            .usageBar(trackedTotal, systemTotalBytes,
                systemTotalBytes > 0
                    ? formatBytes(trackedTotal) + " / " + formatBytes(systemTotalBytes)
                    : formatBytes(trackedTotal));

        // The active CEARL plan's pressure reading (only when it has a budget).
        VramPlan plan = VramPlans.active();
        long softBudget = plan.softBudgetBytes();
        if (softBudget > 0) {
            double pressure = (double) trackedTotal / softBudget;
            panel.row("Plan '" + plan.name() + "'",
                String.format("%.0f%% of %s", pressure * 100.0, formatBytes(softBudget)));
            var shed = plan.shedAt(pressure);
            if (!shed.isEmpty()) {
                panel.row("Pressure", "shed: " + String.join(", ", shed));
            }
        }

        panel.section("By Category");
        boolean anyCategory = false;
        for (GpuMemoryTracker.Category c : GpuMemoryTracker.Category.values()) {
            long bytes = snap.bytesOf(c);
            if (bytes <= 0) continue;
            panel.row(shortCategoryName(c),
                formatBytes(bytes) + " (" + snap.countOf(c) + ")");
            anyCategory = true;
        }
        if (!anyCategory) panel.row("(nothing tracked)", "");

        panel.section("System");
        panel.row("All processes", gpu.systemVramSummary());
        return panel;
    }
}
