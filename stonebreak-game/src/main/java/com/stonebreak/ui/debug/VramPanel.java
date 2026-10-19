package com.stonebreak.ui.debug;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.vram.VramPlan;
import com.openmason.engine.vram.VramPlans;
import com.openmason.engine.ui.masonry.MStatPanel;
import com.stonebreak.world.generation.diffusion.tgmpipe.TGMPipeVram;

import java.util.function.Supplier;

import static com.stonebreak.ui.debug.DebugFormat.formatBytes;
import static com.stonebreak.ui.debug.DebugFormat.shortCategoryName;

/**
 * The left-hand "VRAM (Game)" card: per-process GPU memory from
 * {@link GpuMemoryTracker} by category, the active CEARL plan's pressure, the
 * DaedalusTGM-Exp terrain service's own GPU memory (a separate process, usually
 * on another card: {@link TGMPipeVram}; only while it runs), and the
 * system-wide reading from {@link GpuInfoProbe}.
 */
public final class VramPanel implements DebugPanel {

    private final GpuInfoProbe gpu;
    private final Supplier<TGMPipeVram.Reading> daedalus;

    public VramPanel(GpuInfoProbe gpu) {
        this(gpu, TGMPipeVram.shared()::latest);
    }

    /** {@code daedalus} returns the terrain service's reading, or null to leave its section out. */
    public VramPanel(GpuInfoProbe gpu, Supplier<TGMPipeVram.Reading> daedalus) {
        this.gpu = gpu;
        this.daedalus = daedalus;
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

        addDaedalus(panel, daedalus.get());

        panel.section("System");
        panel.row("All processes", gpu.systemVramSummary());
        return panel;
    }

    /** The terrain service's section: its whole process on its card (NVML: context, kernels and
     *  torch's cache included), torch's allocator, and that card as a whole. Absent without a reading. */
    public static void addDaedalus(MStatPanel panel, TGMPipeVram.Reading r) {
        if (r == null) return;
        panel.section("Daedalus (" + (r.device() == null ? "?" : r.device()) + ")");
        if (!r.onGpu()) {
            panel.row("Device", "CPU (no VRAM)");
            return;
        }
        if (r.processBytes() >= 0) {
            panel.row("Process", formatBytes(r.processBytes()));
        }
        if (r.allocatedBytes() >= 0) {
            panel.row("Tensors", formatBytes(r.allocatedBytes()) + " (peak " + formatBytes(r.peakBytes())
                + ", pool " + formatBytes(r.reservedBytes()) + ")");
        }
        if (r.deviceTotalBytes() > 0) {
            panel.row("Card", formatBytes(r.deviceUsedBytes()) + " / " + formatBytes(r.deviceTotalBytes()));
        }
    }
}
