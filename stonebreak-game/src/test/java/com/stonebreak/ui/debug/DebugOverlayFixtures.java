package com.stonebreak.ui.debug;

import com.openmason.engine.ui.masonry.MStatPanel;
import com.stonebreak.ui.DebugOverlay;

import java.util.List;

/** Synthetic F3 cards for the #301 fidelity cases (fixed numbers, every card shape). Not a test class. */
public final class DebugOverlayFixtures {

    private DebugOverlayFixtures() {
    }

    public static final List<String> VARIANTS = List.of("cards", "with-budgets");

    public static DebugOverlay.Cards cards(String variant) {
        MStatPanel ram = new MStatPanel("RAM (JVM)").usageBar(1_745_000_000L, 4_000_000_000L, "1.62 GB / 3.73 GB")
            .section("Heap Pools").row("ZGC Young", "812.4 MB").row("ZGC Old", "905.1 MB")
            .section("Non-Heap").row("Metaspace", "88.0 MB").row("Code Cache", "41.7 MB").row("Total Non-Heap", "129.7 MB")
            .section("Native Buffers").row("direct", "212.0 MB (1840)").row("mapped", "0 B (0)")
            .section("GC").row("Collections", "214").row("Time Spent", "1832 ms").row("Loaded Classes", "18044");
        MStatPanel vram = new MStatPanel("VRAM").usageBar(7_200_000_000L, 8_000_000_000L, "6.71 GB / 7.45 GB")
            .section("Tracked").row("CHUNK_MESH", "51.2 MB").row("SHADOW_MAP", "48.0 MB")
            .section("Driver").section("Pressure").row("CEARL", "ok");
        MStatPanel budgets = "with-budgets".equals(variant)
            ? new MStatPanel("UI Documents").row("hud", "0.21 ms / 2.1 MB").row("chat", "0.08 ms / 0.9 MB")
            : null;
        MStatPanel debug = new MStatPanel("Debug").usageBar(30, 100, "FPS 144")
            .section("Player").row("Position", "120.52, 64.00, -38.11").row("Chunk", "7, -3")
            .row("Biome", "Snowy Peaks").section("World").row("Looking At", "SNOWY_DIRT").row("Meshes", "1432");
        return new DebugOverlay.Cards(ram, vram, budgets, debug);
    }
}
