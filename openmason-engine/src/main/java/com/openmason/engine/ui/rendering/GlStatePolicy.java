package com.openmason.engine.ui.rendering;

/** What a Skia paint does to the GL state its neighbours rely on once it ends. */
public enum GlStatePolicy {
    /**
     * Capture the GL state before painting and put every piece of it back afterwards
     * ({@link GlStateSnapshot}). Use when someone else owns the pass, such as ImGui drawing the
     * editor, or a preview rendered between viewport passes. Never touches framebuffer 0
     * unless it was bound before.
     */
    RESTORE,
    /**
     * Reset to the baseline the game's renderers were written against ({@link GlBaseline}),
     * with the target's own framebuffer bound. This is the historical Stonebreak behaviour:
     * NanoVG and the world pass after the UI assume it.
     */
    RESET_TO_BASELINE
}
