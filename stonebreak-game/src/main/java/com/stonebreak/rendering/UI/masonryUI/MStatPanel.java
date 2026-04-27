package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable stat-card widget for HUD/debug surfaces — title, usage bar, and a
 * vertical list of section labels and key/value rows.
 *
 * Built on {@link MPainter} so the chrome (stone surface, drop shadow, bevel,
 * noise) automatically tracks the rest of the MasonryUI palette. Card height
 * is derived from the configured rows so the caller can stack panels without
 * pre-computing layout.
 *
 * Usage:
 * <pre>
 * MStatPanel panel = new MStatPanel("RAM (Heap)")
 *     .usageBar(usedBytes, maxBytes)
 *     .section("Heap Pools")
 *     .row("ZGC Young", "120 MB")
 *     .row("ZGC Old", "340 MB");
 * panel.render(ui, x, y, width);
 * </pre>
 */
public final class MStatPanel {

    private static final int   TITLE_COLOR    = MStyle.TEXT_ACCENT;
    private static final int   SECTION_COLOR  = MStyle.TEXT_ACCENT;
    private static final int   LABEL_COLOR    = MStyle.TEXT_SECONDARY;
    private static final int   VALUE_COLOR    = MStyle.TEXT_PRIMARY;
    private static final int   SHADOW_COLOR   = MStyle.TEXT_SHADOW;
    private static final int   BAR_TRACK      = 0xFF1E1E1E;
    private static final int   BAR_FILL_OK    = 0xFF50C878; // green
    private static final int   BAR_FILL_WARN  = 0xFFE6BE3C; // amber
    private static final int   BAR_FILL_CRIT  = 0xFFDC5050; // red
    private static final float TITLE_SIZE     = 16f;
    private static final float SECTION_SIZE   = 13f;
    private static final float ROW_SIZE       = 12f;
    private static final float PADDING        = 10f;
    private static final float TITLE_GAP      = 8f;
    private static final float BAR_HEIGHT     = 7f;
    private static final float BAR_GAP        = 8f;
    private static final float SECTION_GAP    = 6f;
    private static final float ROW_HEIGHT     = 16f;

    private final String title;
    private final List<Entry> entries = new ArrayList<>();

    private boolean hasBar = false;
    private float barFraction = 0f;
    private String barCaption = null;

    public MStatPanel(String title) {
        this.title = title;
    }

    /** Adds a usage bar under the title. Fraction in [0,1]; caption shown right-aligned. */
    public MStatPanel usageBar(long used, long total) {
        return usageBar(used, total, null);
    }

    public MStatPanel usageBar(long used, long total, String caption) {
        this.hasBar = true;
        this.barFraction = total > 0 ? Math.min(1f, Math.max(0f, (float) used / total)) : 0f;
        this.barCaption = caption;
        return this;
    }

    /** Adds a section header (sub-title within the panel). */
    public MStatPanel section(String text) {
        entries.add(new Entry(EntryKind.SECTION, text, null));
        return this;
    }

    /** Adds a key/value row. */
    public MStatPanel row(String label, String value) {
        entries.add(new Entry(EntryKind.ROW, label, value));
        return this;
    }

    /** Total drawn height for a given width. Useful for stacking. */
    public float measureHeight() {
        float h = PADDING + TITLE_SIZE + TITLE_GAP;
        if (hasBar) h += BAR_HEIGHT + BAR_GAP;
        boolean prevWasSection = false;
        for (Entry e : entries) {
            if (e.kind == EntryKind.SECTION) {
                h += (prevWasSection ? 0f : SECTION_GAP) + SECTION_SIZE + 2f;
                prevWasSection = true;
            } else {
                h += ROW_HEIGHT;
                prevWasSection = false;
            }
        }
        h += PADDING;
        return h;
    }

    /**
     * Renders the panel. Returns total height drawn so callers can stack.
     */
    public float render(MasonryUI ui, float x, float y, float width) {
        Canvas canvas = ui.canvas();
        if (canvas == null) return 0f;

        float height = measureHeight();
        MPainter.panel(canvas, x, y, width, height);

        Font titleFont   = ui.fonts().get(TITLE_SIZE);
        Font sectionFont = ui.fonts().get(SECTION_SIZE);
        Font rowFont     = ui.fonts().get(ROW_SIZE);
        if (titleFont == null || sectionFont == null || rowFont == null) {
            return height; // backend not ready — chrome only
        }

        float cursorY = y + PADDING + TITLE_SIZE * 0.85f;
        MPainter.drawStringWithShadow(canvas, title, x + PADDING, cursorY,
                titleFont, TITLE_COLOR, SHADOW_COLOR);
        cursorY += TITLE_SIZE * 0.4f + TITLE_GAP;

        if (hasBar) {
            float barX = x + PADDING;
            float barW = width - PADDING * 2;
            MPainter.fillRoundedRect(canvas, barX, cursorY, barW, BAR_HEIGHT, BAR_HEIGHT * 0.5f, BAR_TRACK);
            int fillColor = barFraction < 0.60f ? BAR_FILL_OK
                          : barFraction < 0.85f ? BAR_FILL_WARN
                                                : BAR_FILL_CRIT;
            float fillW = Math.max(0f, barW * barFraction);
            if (fillW > 0f) {
                MPainter.fillRoundedRect(canvas, barX, cursorY, fillW, BAR_HEIGHT,
                        BAR_HEIGHT * 0.5f, fillColor);
            }
            if (barCaption != null && !barCaption.isEmpty()) {
                float captionW = MPainter.measureWidth(rowFont, barCaption);
                MPainter.drawStringWithShadow(canvas, barCaption,
                        x + width - PADDING - captionW, cursorY - 2f,
                        rowFont, LABEL_COLOR, SHADOW_COLOR);
            }
            cursorY += BAR_HEIGHT + BAR_GAP;
        }

        boolean prevWasSection = false;
        for (Entry e : entries) {
            if (e.kind == EntryKind.SECTION) {
                if (!prevWasSection) cursorY += SECTION_GAP;
                MPainter.drawStringWithShadow(canvas, e.label, x + PADDING, cursorY + SECTION_SIZE * 0.85f,
                        sectionFont, SECTION_COLOR, SHADOW_COLOR);
                cursorY += SECTION_SIZE + 2f;
                prevWasSection = true;
            } else {
                float baseline = cursorY + ROW_SIZE * 0.85f;
                MPainter.drawStringWithShadow(canvas, e.label, x + PADDING + 6f, baseline,
                        rowFont, LABEL_COLOR, SHADOW_COLOR);
                if (e.value != null) {
                    float valW = MPainter.measureWidth(rowFont, e.value);
                    MPainter.drawStringWithShadow(canvas, e.value,
                            x + width - PADDING - valW, baseline,
                            rowFont, VALUE_COLOR, SHADOW_COLOR);
                }
                cursorY += ROW_HEIGHT;
                prevWasSection = false;
            }
        }
        return height;
    }

    private enum EntryKind { SECTION, ROW }

    private static final class Entry {
        final EntryKind kind;
        final String label;
        final String value;

        Entry(EntryKind kind, String label, String value) {
            this.kind = kind;
            this.label = label;
            this.value = value;
        }
    }
}
