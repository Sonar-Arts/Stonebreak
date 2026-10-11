package com.stonebreak.ui.worldHud;

import com.stonebreak.rendering.UI.components.StealthHudRenderer;
import com.stonebreak.rendering.UI.components.WorldMarker;
import com.stonebreak.rendering.UI.components.WorldMarker.Kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * World HUD fixture frames (#301): projected markers as the legacy renderers collect them, at
 * fractional window positions relative to the viewport, plus stealth states. Not a test class.
 */
public final class WorldHudFixtures {

    private WorldHudFixtures() {
    }

    /** One frame of the world HUD. */
    public record Frame(List<WorldMarker> markers, StealthHudRenderer.State stealth) {
    }

    public static final List<String> VARIANTS = List.of("texts", "prey", "prey-empty", "stealth", "entering",
        "cooling", "mixed");

    private static WorldMarker text(String id, Kind kind, float x, float y, String text, float size, int color,
                                    int shadow) {
        return WorldMarker.text(id, kind, x, y, text, size, color, shadow);
    }

    /** {@code variant} on a {@code w × h} window (anchors as fractions of it, so every viewport shows them). */
    public static Frame frame(String variant, int w, int h) {
        List<WorldMarker> m = new ArrayList<>();
        StealthHudRenderer.State stealth = StealthHudRenderer.State.NONE;
        switch (variant) {
            case "texts" -> texts(m, w, h);
            case "prey" -> prey(m, w, h, 0.63f);
            case "prey-empty" -> prey(m, w, h, 0f);
            case "stealth" -> stealth = new StealthHudRenderer.State("STEALTH", StealthHudRenderer.ACTIVE_COLOR, false, 0f);
            case "entering" -> stealth = new StealthHudRenderer.State("ENTERING STEALTH…",
                StealthHudRenderer.ENTERING_COLOR, true, 0.42f);
            case "cooling" -> stealth = new StealthHudRenderer.State("", 0, true, 0.9f);
            case "mixed" -> {
                texts(m, w, h);
                prey(m, w, h, 0.37f);
                stealth = new StealthHudRenderer.State("STEALTH", StealthHudRenderer.ACTIVE_COLOR, true, 0.15f);
            }
            default -> throw new IllegalArgumentException("world HUD variant " + variant);
        }
        return new Frame(List.copyOf(m), stealth);
    }

    private static void texts(List<WorldMarker> m, int w, int h) {
        // damage numbers: full, mid-fade and nearly gone, sizes 28 + 1.5 x damage capped at 44
        m.add(text("damage:1", Kind.DAMAGE, w * 0.31f + 0.37f, h * 0.42f + 9.8f, "4", 34f, 0xFFFFFFFF, 0x991A1A1A));
        m.add(text("damage:2", Kind.DAMAGE, w * 0.36f - 0.21f, h * 0.38f + 15.4f, "12", 44f,
            (int) (0.6f * 255) << 24 | 0x00FFFFFF, (int) (0.6f * 153) << 24 | 0x001A1A1A));
        m.add(text("damage:3", Kind.DAMAGE, w * 0.4f + 0.5f, h * 0.45f, "1", 29.5f,
            (int) (0.2f * 255) << 24 | 0x00FFFFFF, (int) (0.2f * 153) << 24 | 0x001A1A1A));
        m.add(text("doubt:1", Kind.DOUBT, w * 0.55f + 0.25f, h * 0.33f + 0.75f, "Doubt 2/5", 14f, 0xFFC9B8F0, 0x99151515));
        m.add(text("doubt:2", Kind.DOUBT, w * 0.62f, h * 0.36f + 0.5f, "Doubt 5/5", 14f, 0xFFE85C5C, 0x99151515));
        m.add(text("awareness:1", Kind.AWARENESS, w * 0.7f + 0.1f, h * 0.3f, "?", 22f, 0xFFF2D24A, 0xCC101010));
        m.add(text("awareness:2", Kind.AWARENESS, w * 0.74f, h * 0.31f + 0.6f, "!", 22f, 0xFFE0473C, 0xCC101010));
        m.add(text("nametag:1", Kind.NAMETAG, w * 0.2f + 0.45f, h * 0.29f, "Builder_42", 18f, 0xFFFFFFFF, 0xCC101010));
    }

    private static void prey(List<WorldMarker> m, int w, int h, float hp) {
        m.add(new WorldMarker("prey:1", Kind.PREY, w * 0.5f + 0.3f, h * 0.55f + 0.7f, "Ice Archon", 14f, 0xFFFFFFFF,
            0x991A1A1A, hp, 0f));
        m.add(new WorldMarker("trap:1", Kind.TRAP, w * 0.45f + 0.5f, h * 0.7f + 0.25f, "", 0f, 0xC8E08A28, 0, 0f,
            5f * (0.5f + 0.5f * 0.47f)));
        m.add(new WorldMarker("trap:2", Kind.TRAP, w * 0.58f, h * 0.72f, "", 0f, 0xDC58B858, 0, 0f, 5f));
    }

    /**
     * The gate's geometry oracle: every marker's anchor by row index, the stealth label's centre and
     * baseline (window centre, 64 px down) and the re-entry bar (160 x 8, 72 px down), as the legacy
     * renderers place them.
     */
    public static Map<String, float[]> anchors(Frame f, int w) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (int i = 0; i < f.markers().size(); i++) {
            out.put("m" + i, new float[]{f.markers().get(i).x(), f.markers().get(i).y(), 0f, 0f});
        }
        float cx = w * 0.5f;
        if (!f.stealth().label().isEmpty()) {
            out.put("stealth-label", new float[]{cx, 64f, 0f, 0f});
        }
        if (f.stealth().cooling()) {
            out.put("reentry-bar", new float[]{cx - 80f, 72f, 160f, 8f});
        }
        return out;
    }
}
