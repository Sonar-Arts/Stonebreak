package com.stonebreak.ui.debug;

import com.stonebreak.rendering.UI.masonryUI.MStatPanel;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;

import static com.stonebreak.ui.debug.DebugFormat.formatBytes;
import static com.stonebreak.ui.debug.DebugFormat.shortPoolName;

/**
 * The left-hand "RAM (JVM)" card: heap usage bar, per-pool heap and non-heap
 * breakdown, direct/mapped buffer pools and GC counters read from the
 * platform MXBeans.
 */
public final class RamPanel implements DebugPanel {

    /**
     * Builds the RAM card. Combines:
     *   • Heap usage bar (used / max)
     *   • Per-pool heap breakdown (Eden / Survivor / Old, or ZGC pools)
     *   • Non-heap pools (Metaspace, Code Cache, etc.)
     *   • Direct + mapped buffer pools (where LWJGL native data lives)
     *   • GC stats (collections, total time)
     */
    @Override
    public MStatPanel build() {
        Runtime runtime = Runtime.getRuntime();
        long maxBytes = runtime.maxMemory();
        long totalBytes = runtime.totalMemory();
        long freeBytes = runtime.freeMemory();
        long usedBytes = totalBytes - freeBytes;

        MStatPanel panel = new MStatPanel("RAM (JVM)")
            .usageBar(usedBytes, maxBytes,
                formatBytes(usedBytes) + " / " + formatBytes(maxBytes));

        // Heap pools — the "what's in the heap" breakdown.
        panel.section("Heap Pools");
        boolean anyHeap = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;
            MemoryUsage u = pool.getUsage();
            if (u == null) continue;
            panel.row(shortPoolName(pool.getName()), formatBytes(u.getUsed()));
            anyHeap = true;
        }
        if (!anyHeap) panel.row("(none reported)", "");

        // Non-heap pools — Metaspace, Code Cache, Compressed Class.
        panel.section("Non-Heap");
        long nonHeapTotal = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.NON_HEAP) continue;
            MemoryUsage u = pool.getUsage();
            if (u == null) continue;
            nonHeapTotal += u.getUsed();
            panel.row(shortPoolName(pool.getName()), formatBytes(u.getUsed()));
        }
        panel.row("Total Non-Heap", formatBytes(nonHeapTotal));

        // Direct buffer pool — this is where LWJGL keeps native memory the JVM
        // owns but ZGC doesn't manage. Often 2nd biggest after heap for us.
        panel.section("Native Buffers");
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            String name = pool.getName(); // "direct" or "mapped"
            long used = pool.getMemoryUsed();
            long count = pool.getCount();
            panel.row(name, formatBytes(used < 0 ? 0 : used) + " (" + count + ")");
        }

        // GC stats — gives a hint at allocation pressure.
        long gcCollections = 0;
        long gcTimeMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gc.getCollectionCount();
            long t = gc.getCollectionTime();
            if (c > 0) gcCollections += c;
            if (t > 0) gcTimeMs += t;
        }
        panel.section("GC");
        panel.row("Collections", String.valueOf(gcCollections));
        panel.row("Time Spent", gcTimeMs + " ms");
        panel.row("Loaded Classes",
            String.valueOf(ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()));
        return panel;
    }
}
