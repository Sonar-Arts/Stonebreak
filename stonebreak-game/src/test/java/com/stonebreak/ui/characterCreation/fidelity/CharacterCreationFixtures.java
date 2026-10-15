package com.stonebreak.ui.characterCreation.fidelity;

import com.openmason.engine.format.omui.UiValue;
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
import com.stonebreak.items.weapon.WeaponGroup;
import com.stonebreak.rpg.SkillListMode;
import com.stonebreak.rpg.TalentSubTab;
import com.stonebreak.ui.runtime.contracts.CharacterCreationContracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Character creation's #301 fidelity cases: the screen's state per variant (one per tab, a class with and
 * without a selection, scrolled lists) and the legacy layout as the geometry oracle. Not a test class.
 *
 * <p>Variants: {@code background}, {@code ability}, {@code classes-none}, {@code classes}, {@code classes-scrolled},
 * {@code skills}, {@code skills-scrolled}, {@code feats}, {@code feats-owned}, {@code looks} (hair and hats side by side); {@code hover-<target>}
 * over the state that shows it: {@code tab3}, {@code next}, {@code back}, {@code undo}, {@code bg1}, {@code tile2},
 * {@code plus0}, {@code minus4}, {@code talent1}, {@code cls2}, {@code node5} (a class tree node), {@code invest2},
 * {@code acquire0},
 * {@code equip1}. Class, skill and feat states are the Talents tab on that sub-tab.
 */
public final class CharacterCreationFixtures {

    private CharacterCreationFixtures() {
    }

    public static final List<String> STATES = List.of("ability", "classes-none", "classes", "classes-scrolled", "skills",
        "skills-scrolled", "skills-weapons", "feats", "feats-owned", "looks");
    public static final List<String> HOVERS = List.of("hover-tab3", "hover-next", "hover-back", "hover-undo",
        "hover-bg1", "hover-tile2", "hover-plus0", "hover-minus4", "hover-talent1", "hover-cls2", "hover-node5",
        "hover-invest2", "hover-mode1", "hover-train0", "hover-acquire0", "hover-equip1");

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

    private static float num(UiValue.Obj o, String key) {
        return (float) ((UiValue.Num) o.get(key)).value();
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
        if (t.startsWith("cls") || t.startsWith("node") || t.startsWith("talent")) return "classes";
        if (t.startsWith("invest")) return "skills";
        if (t.startsWith("mode") || t.startsWith("train")) return "skills-weapons";
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
            case "classes-none" -> talents(s, TalentSubTab.CLASS_ABILITIES);
            case "classes", "classes-scrolled" -> {
                // Berserker: ability icons and a Tier I rank that unlocks Rampage; Monk: placeholder abilities
                int cls = "classes-scrolled".equals(variant) ? 2 : 0;
                talents(s, TalentSubTab.CLASS_ABILITIES);
                a.onSelectClass(cls);
                a.onSpendCp(ClassRegistry.pathKey(ClassRegistry.ALL.get(cls).id(), 0, 0));
                if ("classes-scrolled".equals(variant)) s.setClassScroll(13f);
            }
            case "skills", "skills-scrolled" -> {
                talents(s, TalentSubTab.SKILLS);
                a.onInvestSkill(SkillRegistry.ALL.get(2).id());
                if ("skills-scrolled".equals(variant)) s.setSkillScroll(48f);
            }
            case "skills-weapons" -> {
                // the starting WTP spent on one group; the others show their next rank or level gate
                talents(s, TalentSubTab.SKILLS);
                a.onSelectSkillMode(SkillListMode.WEAPONS);
                a.onTrainWeapon(WeaponGroup.ONE_HANDED);
            }
            case "feats", "feats-owned" -> {
                talents(s, TalentSubTab.FEATS);
                if ("feats-owned".equals(variant)) a.onAcquireFeat(FeatRegistry.ALL.get(0).id());
            }
            case "looks" -> s.setActiveTab(CharacterCreationTab.LOOKS);
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
        if (screen.state().getActiveTab() == CharacterCreationTab.TALENTS) {
            // the legacy MTabBar 8 px into the content, 30 high, its slots splitting it 2 px apart; the
            // sub-tab's pane 6 px under it
            float barW = c.width() - 16f;
            for (int i = 0; i < 3; i++) {
                out.put("talent" + i, new float[]{c.x() + 8f + i * (barW / 3f), c.y() + 8f,
                    barW / 3f - (i < 2 ? 2f : 0f), 30f});
            }
            float subY = c.y() + 8f + 30f + 6f;
            c = new CharacterCreationLayout.Rect(c.x(), subY, c.width(), c.bottom() - subY);
        }
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
                // the class tree's nodes: the shared tree model's grid, placed in the pane's tree area and scrolled
                if (s.getSelectedClassIndex() >= 0) {
                    UiValue.Obj tree = (UiValue.Obj) CharacterCreationContracts.tree(screen, w, h);
                    List<UiValue> nodes = ((UiValue.Arr) tree.get("nodes")).items();
                    for (int i = 0; i < nodes.size(); i++) {
                        UiValue.Obj n = (UiValue.Obj) nodes.get(i);
                        float size = num(n, "size");
                        out.put("node" + i, new float[]{c.x() + CharacterCreationContracts.TREE_LEFT + num(n, "x"),
                            c.y() + CharacterCreationContracts.TREE_TOP + num(n, "y") - s.getClassScroll(), size, size});
                    }
                }
            }
            case "skills", "skills-scrolled", "skills-weapons" -> {
                // the General | Weapons toggle, right-aligned in the header; then +1 SP or Train per row
                out.put("mode0", new float[]{c.x() + c.width() - 116f - 96f, c.y() + 2f, 96f, 24f});
                out.put("mode1", new float[]{c.x() + c.width() - 16f - 96f, c.y() + 2f, 96f, 24f});
                if (s.getSkillMode() == SkillListMode.WEAPONS) {
                    for (int i = 0; i < WeaponGroup.values().length; i++) {
                        out.put("train" + i, new float[]{listX + rowW - 80f, c.y() + 36f + i * 42f + 7f, 80f, 26f});
                    }
                } else {
                    for (int i = 0; i < SkillRegistry.ALL.size(); i++) {
                        out.put("invest" + i, new float[]{listX + rowW - 80f,
                            c.y() + 36f + i * 42f - s.getSkillScroll() + 7f, 80f, 26f});
                    }
                }
            }
            case "feats", "feats-owned" -> {
                for (int i = 0; i < FeatRegistry.ALL.size(); i++) {
                    if (s.getCharacterStats().hasFeat(FeatRegistry.ALL.get(i).id())) continue; // no button
                    out.put("acquire" + i, new float[]{listX + rowW - 100f, c.y() + 36f + i * 58f + 13f, 100f, 28f});
                }
            }
            case "looks" -> {
                // two columns, each half the content: hair ("equipN") on the left, hats ("hatN") on the right
                float colW = c.width() / 2f;
                cosmetics(out, "equip", com.stonebreak.player.PlayerLooks.HAIR_OPTIONS,
                    com.stonebreak.player.PlayerLooks.getSelectedHairId(), c.x() + colW - 16f, c.y());
                cosmetics(out, "hat", com.stonebreak.player.PlayerLooks.HAT_OPTIONS,
                    com.stonebreak.player.PlayerLooks.getSelectedHatId(), c.right() - 16f, c.y());
            }
            default -> {
            }
        }
        return out;
    }

    /** One cosmetic list's Equip buttons, right-aligned to {@code right}; the equipped row shows none. */
    private static void cosmetics(Map<String, float[]> out, String kind,
                                  List<com.stonebreak.player.PlayerLooks.CosmeticOption> options, String equipped,
                                  float right, float top) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id().equalsIgnoreCase(equipped)) continue; // "Equipped", no button
            out.put(kind + i, new float[]{right - 100f, top + 36f + i * 58f + 13f, 100f, 28f});
        }
    }

    /**
     * The footer buttons of the legacy layout: Back 16 px in, Next 16 px from the right, Undo centred, each
     * {@code MButton.preferredWidth} wide (measured with {@code ui}'s fonts) and 44 high, 14 below the footer's top.
     */
    public static Map<String, float[]> footerButtons(CharacterCreationScreen screen,
                                                     com.openmason.engine.ui.masonry.MasonryUI ui, int w, int h) {
        CharacterCreationLayout.Rect f = screen.layout().footer(w, h);
        CharacterCreationStateManager s = screen.state();
        float y = f.y() + (f.height() - 44f) / 2f;
        float back = s.getBackToWorldSelectButton().preferredWidth(ui);
        float next = s.getTerrainMapperButton().preferredWidth(ui);
        float undo = s.getUndoButton().preferredWidth(ui);
        Map<String, float[]> out = new LinkedHashMap<>();
        out.put("back", new float[]{f.x() + 16f, y, back, 44f});
        out.put("next", new float[]{f.x() + f.width() - next - 16f, y, next, 44f});
        out.put("undo", new float[]{f.x() + (f.width() - undo) / 2f, y, undo, 44f});
        return out;
    }

    private static void put(Map<String, float[]> out, String name, CharacterCreationLayout.Rect r) {
        out.put(name, new float[]{r.x(), r.y(), r.width(), r.height()});
    }

    private static void talents(CharacterCreationStateManager s, TalentSubTab sub) {
        s.setActiveTab(CharacterCreationTab.TALENTS);
        s.setTalentSubTab(sub);
    }
}
