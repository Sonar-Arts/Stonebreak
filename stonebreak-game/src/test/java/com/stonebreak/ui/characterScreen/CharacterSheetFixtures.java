package com.stonebreak.ui.characterScreen;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.stonebreak.config.Settings;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rpg.CharacterPanelTab;
import com.stonebreak.rpg.TalentSubTab;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.rpg.skills.SkillRegistry;
import com.stonebreak.ui.TabStripLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The character sheet's #301 fidelity cases: a sheet over fixed stats in each tab's states, and the legacy layout as
 * the geometry oracle (panel, tab strip, ability tiles, class rows, +1 SP buttons, feat rows and buttons). Not a test
 * class.
 */
public final class CharacterSheetFixtures {

    private CharacterSheetFixtures() {
    }

    public static final List<String> STATES = List.of("overview-busy", "classes", "classes-shown", "classes-scrolled",
        "skills", "feats", "feats-selected", "feats-filtered");
    public static final List<String> HOVERS = List.of("hover-tab0", "hover-tab2", "hover-tile1", "hover-plus2",
        "hover-talent1", "hover-cls1", "hover-invest3", "hover-feat0", "hover-level", "hover-general",
        "hover-acquire");

    public static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("character-sheet", List.of("overview"),
            FidelityCase.STANDARD));
        for (String v : STATES) {
            out.add(new FidelityCase("character-sheet", v, FidelityCase.STANDARD.get(0)));
        }
        out.add(new FidelityCase("character-sheet", "classes-shown", FidelityCase.STANDARD.get(3)));
        out.add(new FidelityCase("character-sheet", "feats-selected", FidelityCase.STANDARD.get(1)));
        for (String h : HOVERS) {
            out.add(new FidelityCase("character-sheet", h, FidelityCase.STANDARD.get(0)));
        }
        out.add(new FidelityCase("character-sheet", "hover-invest3", FidelityCase.STANDARD.get(3)));
        return out;
    }

    public static String hover(String variant) {
        return variant.startsWith("hover-") ? variant.substring("hover-".length()) : "";
    }

    public static String base(String variant) {
        String t = hover(variant);
        if (t.isEmpty()) return variant;
        if (t.startsWith("tile") || t.startsWith("plus")) return "overview";
        if (t.startsWith("cls") || t.startsWith("talent")) return "classes-shown";
        if (t.startsWith("invest")) return "skills";
        if (t.startsWith("acquire")) return "feats-selected";
        if (t.startsWith("feat") || t.equals("level") || t.equals("general")) return "feats";
        return "overview";
    }

    /** A sheet over fresh stats, its text measured with {@code backend}'s fonts. */
    public static CharacterScreen screen(SkijaUIBackend backend) {
        Renderer renderer = mock(Renderer.class);
        when(renderer.getSkijaBackend()).thenReturn(backend);
        CharacterScreen screen = new CharacterScreen(new CharacterStats(null), renderer);
        screen.getController().setVisible(true);
        return screen;
    }

    public static void prepare(CharacterScreen screen, String variant) {
        CharacterController rc = screen.getController();
        CharacterStats stats = rc.stats();
        var controller = screen.getController();
        String b = base(variant);
        switch (b) {
            case "overview", "overview-busy" -> {
                controller.setActiveTab(CharacterPanelTab.OVERVIEW);
                if ("overview-busy".equals(b)) {
                    stats.incrementAbilityScore(0);
                    stats.incrementAbilityScore(2);
                    stats.addXp(130);
                    stats.acquireFeat(FeatRegistry.ALL.get(0).id());
                    stats.investSkillPoint(SkillRegistry.ALL.get(0).id());
                }
            }
            case "classes" -> talents(controller, TalentSubTab.CLASS_ABILITIES);
            case "classes-shown" -> {
                talents(controller, TalentSubTab.CLASS_ABILITIES);
                rc.classes().selectClass(ClassRegistry.ALL.get(0).id());
                rc.classes().spend(stats, 0);
            }
            case "classes-scrolled" -> {
                talents(controller, TalentSubTab.CLASS_ABILITIES);
                rc.classes().selectClass(ClassRegistry.ALL.get(2).id()); // Monk: five abilities
                rc.classes().spend(stats, 4);
            }
            case "skills" -> {
                talents(controller, TalentSubTab.SKILLS);
                stats.investSkillPoint(SkillRegistry.ALL.get(1).id());
            }
            case "feats" -> talents(controller, TalentSubTab.FEATS);
            case "feats-selected" -> {
                talents(controller, TalentSubTab.FEATS);
                rc.feats().selectFeat(FeatRegistry.filter(0, false).get(0).id());
            }
            case "feats-filtered" -> {
                talents(controller, TalentSubTab.FEATS);
                rc.feats().cycleLevel(1);
                rc.feats().toggleGeneral();
            }
            default -> throw new IllegalArgumentException(variant);
        }
    }

    private static void talents(CharacterController controller, TalentSubTab sub) {
        controller.setActiveTab(CharacterPanelTab.TALENTS);
        controller.setTalentSubTab(sub);
    }

    /** After the document's first poll: the scrolls the wheel reaches (the clamp needs the clip it publishes). */
    public static void scroll(CharacterScreen screen, String variant) {
        if ("classes-scrolled".equals(base(variant))) {
            float s = Settings.getInstance().getUiScale();
            screen.getController().classes().scroll(1f, false, s); // the ability list, one notch
        }
    }

    /** Named rects of the legacy layout. */
    public static Map<String, float[]> rects(CharacterScreen screen, String variant, int w, int h) {
        float s = Settings.getInstance().getUiScale();
        float[] p = CharacterController.panel(w, h, s);
        Map<String, float[]> out = new LinkedHashMap<>();
        out.put("panel", p);
        int tabW = TabStripLayout.tabWidth();
        int tabH = TabStripLayout.tabHeight();
        int tabY = TabStripLayout.tabY(Math.round(p[1]));
        int startX = TabStripLayout.startX(w);
        for (int i = 0; i < 3; i++) {
            out.put("tab" + i, new float[]{startX + TabStripLayout.stride() * i, tabY, tabW, tabH});
        }
        CharacterController rc = screen.getController();
        if (rc.getActiveTab() == CharacterPanelTab.TALENTS) {
            // the legacy MTabBar: (600 - 20) s wide, 10 s in, 16 s down, 28 s high; slots split it, 2 px apart
            float barW = (600f - 20f) * s;
            float slotW = barW / 3f;
            for (int i = 0; i < 3; i++) {
                out.put("talent" + i, new float[]{p[0] + 10f * s + i * slotW, p[1] + 16f * s,
                    slotW - (i < 2 ? 2f : 0f), 28f * s});
            }
            p = new float[]{p[0], p[1] + 50f * s, p[2], p[3]}; // the panes start under the bar
        }
        switch (com.stonebreak.ui.runtime.contracts.CharacterSheetContracts.pane(rc)) {
            case "OVERVIEW" -> {
                float leftColX = p[0] + 16 * s;
                float leftColW = p[2] * 0.38f;
                float rightColX = leftColX + leftColW + 12f * s;
                float contentY = p[1] + 20f * s;
                float barH = Math.round(10 * s);
                float barY = contentY + 14f * s + 4f * s;
                float abilitiesY = barY + barH + 6f * s;
                for (int i = 0; i < 6; i++) {
                    float tx = rightColX + (i % 3) * (64f * s + 8f * s);
                    float ty = abilitiesY + (i / 3) * (80f * s + 8f * s);
                    out.put("tile" + i, new float[]{tx, ty, 64f * s, 80f * s});
                    out.put("plus" + i, new float[]{tx + 64f * s - 16f * s - 2f, ty + 34f * s, 16f * s, 14f * s});
                }
            }
            case "CLASSES" -> {
                float clipTop = p[1] + 20f * s + 30f * s;
                for (int i = 0; i < ClassRegistry.ALL.size(); i++) {
                    out.put("cls" + i, new float[]{p[0] + 10f * s + 1f, clipTop + i * (22f * s + 2f * s)
                        - rc.classes().leftScroll(), 163f * s, 22f * s});
                }
            }
            case "SKILLS" -> {
                for (int i = 0; i < SkillRegistry.ALL.size(); i++) {
                    out.put("invest" + i, new float[]{p[0] + 490f * s, p[1] + 48f * s + i * (24f * s) + 1f, 86f * s,
                        22f * s});
                }
            }
            case "FEATS" -> {
                var feats = FeatRegistry.filter(rc.feats().filterLevel(), rc.feats().filterGeneral());
                for (int i = 0; i < feats.size(); i++) {
                    out.put("feat" + i, new float[]{p[0] + 10f * s, p[1] + 58f * s + i * (24f * s)
                        - rc.feats().scrollOffset(), 370f * s - 8f, 24f * s});
                }
                float barX = p[0] + 10f * s;
                float btnY = p[1] + 18f * s + (32f * s - 22f * s) / 2f;
                out.put("level", new float[]{barX + 6f * s, btnY, 100f * s, 22f * s});
                out.put("general", new float[]{barX + (6f + 108f) * s, btnY, 84f * s, 22f * s});
                if (rc.feats().selectedFeatId() != null) {
                    out.put("acquire", new float[]{p[0] + 396f * s + (194f * s - 140f * s) / 2f, p[1] + 400f * s,
                        140f * s, 26f * s});
                }
            }
        }
        return out;
    }
}
