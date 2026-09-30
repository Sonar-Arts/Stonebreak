package com.openmason.main.systems.mortar.theme;

import com.openmason.main.platform.ImGuiBackend;

/**
 * MortarUI's type ramp: the one set of text sizes every Skija/Mortar surface
 * draws with, anchored to the ImGui body size so Mortar text sits in a fixed
 * proportion to the ImGui text beside it.
 *
 * <p>Sizes are <em>logical</em> px. {@link com.openmason.main.systems.mortar.core.MortarRegion}
 * scales the whole canvas by {@link MortarTheme#scale} (UI density), exactly as
 * ImGui scales its own text, so these never need multiplying by hand.</p>
 *
 * <pre>
 *   ImGui body 16  →  TITLE 16 · BODY 14 · CONTROL 13 · LABEL 12 · CAPTION 11
 * </pre>
 *
 * <p>Decorative glyphs sized to their geometry ("+", arrows, monograms) are
 * icons, not text, and keep their own local constants.</p>
 */
public final class MortarType {

    /** ImGui body text size (the font atlas size); the ramp's anchor. */
    public static final float IMGUI_BODY = ImGuiBackend.FONT_SIZE;

    /** Headings: card/section titles, the hub wordmark. Same size as ImGui body text. */
    public static final float TITLE = IMGUI_BODY;
    /** Primary readable text: list-row and nav titles, prose. */
    public static final float BODY = IMGUI_BODY * 14f / 16f;
    /** Control labels: buttons, tabs, pills, action chips, foldout headers. */
    public static final float CONTROL = IMGUI_BODY * 13f / 16f;
    /** Secondary text: descriptions, subtitles, trailing meta. */
    public static final float LABEL = IMGUI_BODY * 12f / 16f;
    /** Smallest text: badges, section labels, footers, in-tile labels. */
    public static final float CAPTION = IMGUI_BODY * 11f / 16f;

    private MortarType() {
    }
}
