package com.stonebreak.ui.characterCreation.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.stonebreak.rpg.backgrounds.BackgroundRegistry;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.rpg.skills.SkillRegistry;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout;
import com.stonebreak.ui.characterCreation.CharacterCreationScreen;
import com.stonebreak.ui.characterCreation.CharacterCreationStateManager;
import com.stonebreak.ui.characterCreation.CharacterCreationTab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Character creation's #301 fidelity cases: the screen's state per variant (one per tab, a class with and
 * without a selection, scrolled lists) and the legacy layout as the geometry oracle. Not a test class.
 *
 * <p>Variants: {@code background}, {@code ability}, {@code classes-none}, {@code classes}, {@code classes-scrolled},
 * {@code skills}, {@code skills-scrolled}, {@code feats}, {@code feats-owned}, {@code looks}, {@code clothing}; {@code hover-<target>}
 * over the state that shows it: {@code tab3}, {@code next}, {@code back}, {@code bg1}, {@code tile2}, {@code plus0},
 * {@code minus4}, {@code cls2}, {@code spend1}, {@code invest2}, {@code acquire0}, {@code equip1}.
 */
public final class CharacterCreationFixtures {

    private CharacterCreationFixtures() {
    }

    public static final List<String> STATES = List.of("ability", "classes-none", "classes", "classes-scrolled", "skills",
        "skills-scrolled", "feats", "feats-owned", "looks", "clothing");
    public static final List<String> HOVERS = List.of("hover-tab3", "hover-next", "hover-back", "hover-bg1",
        "hover-tile2", "hover-plus0", "hover-minus4", "hover-cls2", "hover-spend1", "hover-invest2", "hover-acquire0",
        "hover-equip1");

    public static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("character-creation", List.of("background"),
            FidelityCase.STANDARD));
        for (String v : STATES) {
            out.add(new FidelityCase("character-creation", v, FidelityCase.STANDARD.get(0)));
        }
        out.add(new FidelityCase("character-creation", "classes", FidelityCase.STANDARD.get(1)));
        for (String h : HOVERS) {
            out.add(new FidelityCase("character-creation", h, FidelityCase.STANDARD.get(0)));
        }
        out.add(new FidelityCase("character-creation", "hover-next", FidelityCase.STANDARD.get(3)));
        return out;
    }

    public static String hover(String variant) {
        return variant.startsWith("hover-") ? variant.substring("hover-".length()) : "";
    }

    /** The state a hover case shows its target in. */
    public static String base(String variant) {
        String t = hover(variant);
        if (t.isEmpty()) {
            return variant;
        }
        if (t.startsWith("tile") || t.startsWith("plus") || t.startsWith("minus")) return "ability";
        if (t.startsWith("cls") || t.startsWith("spend")) return "classes";
        if (t.startsWith("invest")) return "skills";
        if (t.startsWith("acquire")) return "feats";
        if (t.startsWith("equip")) return "looks";
        return "background";
    }

    /** Puts a fresh screen into {@code variant}'s state through its own actions. */
    public static void prepare(CharacterCreationScreen screen, String variant) {
        CharacterCreationStateManager s = screen.state();
        CharacterCreationActionHandler a = screen.actions();
        a.onSelectBackground(BackgroundRegistry.ALL.get(1).id());
        a.onAbilityIncrement(0);
        a.onAbilityIncrement(0);
        a.onAbilityDecrement(4);
        switch (base(variant)) {
            case "background" -> s.setActiveTab(CharacterCreationTab.BACKGROUND);
            case "ability" -> s.setActiveTab(CharacterCreationTab.ABILITY_SCORE);
            case "classes-none" -> s.setActiveTab(CharacterCreationTab.CLASS_ABILITIES);
            case "classes", "classes-scrolled" -> {
                // Berserker: icons and multi-line descriptions; Monk: five abilities, enough to scroll
                int cls = "classes-scrolled".equals(variant) ? 2 : 0;
                s.setActiveTab(CharacterCreationTab.CLASS_ABILITIES);
                a.onSelectClass(cls);
                a.onSpendCp(ClassRegistry.ALL.get(cls).id() + ":0");
                if ("classes-scrolled".equals(variant)) s.setClassScroll(13f);
            }
            case "skills", "skills-scrolled" -> {
                s.setActiveTab(CharacterCreationTab.SKILLS);
                a.onInvestSkill(SkillRegistry.ALL.get(2).id());
                if ("skills-scrolled".equals(variant)) s.setSkillScroll(48f);
            }
            case "feats", "feats-owned" -> {
                s.setActiveTab(CharacterCreationTab.FEATS);
                if ("feats-owned".equals(variant)) a.onAcquireFeat(FeatRegistry.ALL.get(0).id());
            }
            case "looks" -> s.setActiveTab(CharacterCreationTab.LOOKS);
            case "clothing" -> s.setActiveTab(CharacterCreationTab.CLOTHING);
            default -> throw new IllegalArgumentException(variant);
        }
    }

    /** Named rects of the legacy layout for {@code variant} ({@code tabs}: the screen's own tab metrics). */
    public static Map<String, float[]> rects(CharacterCreationScreen screen, String variant, int w, int h) {
        CharacterCreationLayout l = screen.layout();
        CharacterCreationLayout.Rect right = l.rightPanel(w, h);
        CharacterCreationLayout.Rect bar = l.tabBar(right);
        CharacterCreationLayout.Rect c = l.tabContent(right, bar);
        Map<String, float[]> out = new LinkedHashMap<>();
        put(out, "left", l.leftPanel(w, h));
        put(out, "right", right);
        put(out, "footer", l.footer(w, h));
        var tabs = screen.tabMetrics(w, h);
        for (int i = 0; i < tabs.length; i++) {
            out.put("tab" + i, new float[]{tabs[i].x(), bar.y(), tabs[i].width(), 36f});
        }
        CharacterCreationStateManager s = screen.state();
        float listX = c.x() + 16f;
        float rowW = c.width() - 32f;
        switch (base(variant)) {
            case "background" -> {
                for (int i = 0; i < BackgroundRegistry.ALL.size(); i++) {
                    out.put("bg" + i, new float[]{listX, c.y() + 36f + i * 64f, rowW, 60f});
                }
            }
            case "ability" -> {
                float gridX = c.x() + (c.width() - 294f) / 2f;
                for (int i = 0; i < 6; i++) {
                    float tx = gridX + (i % 3) * 102f;
                    float ty = c.y() + 36f + (i / 3) * 112f;
                    out.put("tile" + i, new float[]{tx, ty, 90f, 100f});
                    out.put("minus" + i, new float[]{tx + 2f, ty + 44f, 20f, 16f});
                    out.put("plus" + i, new float[]{tx + 68f, ty + 44f, 20f, 16f});
                }
            }
            case "classes", "classes-scrolled", "classes-none" -> {
                for (int i = 0; i < ClassRegistry.ALL.size(); i++) {
                    out.put("cls" + i, new float[]{c.x() + 8f, c.y() + 32f + i * 38f, 144f, 36f});
                }
                int sel = s.getSelectedClassIndex();
                if (sel >= 0) {
                    float cx = c.x() + 172f;
                    float cw = c.width() - 184f;
                    for (int i = 0; i < ClassRegistry.ALL.get(sel).abilities().size(); i++) {
                        out.put("spend" + i, new float[]{cx + cw - 110f, c.y() + 78f + i * 64f - s.getClassScroll() + 20f,
                            110f, 24f});
                    }
                }
            }
            case "skills", "skills-scrolled" -> {
                for (int i = 0; i < SkillRegistry.ALL.size(); i++) {
                    out.put("invest" + i, new float[]{listX + rowW - 80f, c.y() + 36f + i * 42f - s.getSkillScroll() + 7f,
                        80f, 26f});
                }
            }
            case "feats", "feats-owned" -> {
                for (int i = 0; i < FeatRegistry.ALL.size(); i++) {
                    if (s.getCharacterStats().hasFeat(FeatRegistry.ALL.get(i).id())) continue; // no button
                    out.put("acquire" + i, new float[]{listX + rowW - 100f, c.y() + 36f + i * 58f + 13f, 100f, 28f});
                }
            }
            case "looks", "clothing" -> {
                boolean looks = "looks".equals(base(variant));
                var options = looks ? com.stonebreak.player.PlayerLooks.HAIR_OPTIONS
                    : com.stonebreak.player.PlayerLooks.HAT_OPTIONS;
                String equipped = looks ? com.stonebreak.player.PlayerLooks.getSelectedHairId()
                    : com.stonebreak.player.PlayerLooks.getSelectedHatId();
                for (int i = 0; i < options.size(); i++) {
                    if (options.get(i).id().equalsIgnoreCase(equipped)) continue; // "Equipped", no button
                    out.put("equip" + i, new float[]{listX + rowW - 100f, c.y() + 36f + i * 58f + 13f, 100f, 28f});
                }
            }
            default -> {
            }
        }
        return out;
    }

    private static void put(Map<String, float[]> out, String name, CharacterCreationLayout.Rect r) {
        out.put(name, new float[]{r.x(), r.y(), r.width(), r.height()});
    }
}
