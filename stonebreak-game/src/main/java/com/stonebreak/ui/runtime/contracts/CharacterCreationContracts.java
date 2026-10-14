package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.PlayerLooks;
import com.stonebreak.items.weapon.WeaponGroup;
import com.stonebreak.rpg.SkillListMode;
import com.stonebreak.rpg.TalentSubTab;
import com.stonebreak.rpg.proficiency.WeaponTraining;
import com.stonebreak.rpg.backgrounds.BackgroundDefinition;
import com.stonebreak.rpg.backgrounds.BackgroundRegistry;
import com.stonebreak.rpg.classes.ClassAbility;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.classes.PlayerClassDefinition;
import com.stonebreak.rpg.feats.FeatDefinition;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.rpg.skills.SkillDefinition;
import com.stonebreak.rpg.skills.SkillRegistry;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout;
import com.stonebreak.ui.characterCreation.CharacterCreationScreen;
import com.stonebreak.ui.characterCreation.CharacterCreationStateManager;
import com.stonebreak.ui.characterCreation.CharacterCreationTab;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout.TabMetric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Character creation ({@code stonebreak:screen.character-creation} 2: #301, v2 the Daedalus Talents tab and Undo). {@link CharacterCreationScreen}
 * stays the controller (its stats, the scroll offsets, every rule, Back/Next and join mode); the document shows
 * it. Root {@code characterCreation}: the active {@code tab} and the {@code pane} shown (the tab, or inside Talents
 * its sub-tab: CLASS_ABILITIES / SKILLS / FEATS), the window {@code layout} (panels, tab strip, content, the Talents
 * sub-tab bar and the content under it, the preview frame: float window pixels, as {@link CharacterCreationLayout}
 * computes them), {@code tabs} (label geometry measured with the game font), the sub-tab bar's {@code talents}
 * (slot x/width within the bar, as MTabBar splits it) and {@code talentBaseline}, footer labels and {@code undoDisabled}, the preview labels, the four point counters, the class
 * header, the six ability {@code tiles}, the three clamped scroll offsets and the Skills pane's list
 * ({@code isGeneral} / {@code isWeapons}, {@code wtpHint}). Collections {@code ccBackgrounds},
 * {@code ccClasses}, {@code ccAbilities} (the selected class's), {@code ccSkills}, {@code ccWeapons} (one per weapon
 * group, v3), {@code ccFeats}, {@code ccHair}, {@code ccHats}.
 *
 * <p>Actions: {@code .tab {tab}}, {@code .talent {sub}} (a {@link TalentSubTab} name), {@code .nav {name}}
 * (back / next / undo: the last point allocation), {@code .background {id}},
 * {@code .ability {index, delta}}, {@code .class {id}}, {@code .spend {index}}, {@code .skill {id}},
 * {@code .skill-mode {mode}} (general, weapons), {@code .train {id}} (a weapon group name, spends WTP),
 * {@code .feat {id}}, {@code .hair {id}}, {@code .hat {id}}, {@code .wheel {dy}}; each refuses unless the screen
 * is showing, and runs the click rule of that control (spending needs points, an equipped cosmetic or an
 * acquired feat takes no press).
 */
public final class CharacterCreationContracts {

    public static final HostContract CREATION = HostContract.of("stonebreak:screen.character-creation", 3);

    private static final String[] ABBREV = {"STR", "DEX", "CON", "INT", "WIS", "CHA"};
    /** The Talents sub-tab bar (MTabBar, labels unscaled): inset, height, the gap above the pane under it. */
    private static final float TALENT_BAR_PAD = 8f;
    private static final float TALENT_BAR_H = 30f;
    private static final float TALENT_CONTENT_GAP = 6f;
    private static final String[] TALENT_LABELS = {"Class Abilities", "Skills", "Feats"};

    private static final DataType RECT = DataType.list(DataType.number(), null);
    private static final DataType.Obj ICON = DataType.object("path", DataType.string());
    public static final DataType.Obj STATE = DataType.object(
        "tab", DataType.string(), "pane", DataType.string(),
        "layout", DataType.object("left", RECT, "right", RECT, "footer", RECT, "tabBar", RECT, "content", RECT,
            "talentBar", RECT, "talentContent", RECT,
            "preview", RECT, "hasPreview", DataType.bool(), "gridX", DataType.number(), "lvlX", DataType.number()),
        "talents", DataType.list(DataType.object("sub", DataType.string(), "label", DataType.string(),
            "x", DataType.number(), "w", DataType.number(), "selected", DataType.bool()), "sub"),
        "talentBaseline", DataType.number(), "undoDisabled", DataType.bool(),
        "tabs", DataType.list(DataType.object("tab", DataType.string(), "label", DataType.string(),
            "x", DataType.number(), "width", DataType.number(), "fontSize", DataType.number(),
            "baseline", DataType.number(), "active", DataType.bool()), "tab"),
        "backLabel", DataType.string(), "nextLabel", DataType.string(),
        "classLabel", DataType.string(), "backgroundLabel", DataType.string(),
        "ap", DataType.string(), "cp", DataType.string(), "sp", DataType.string(), "fp", DataType.string(),
        "className", DataType.string(), "classDesc", DataType.string(), "noClass", DataType.bool(),
        "tiles", DataType.list(DataType.object("tile", DataType.string(), "abbrev", DataType.string(),
            "score", DataType.string(), "mod", DataType.string(), "bonus", DataType.string(),
            "bonusUp", DataType.bool(), "minusEnabled", DataType.bool(), "plusEnabled", DataType.bool()), "tile"),
        "classScroll", DataType.number(), "skillScroll", DataType.number(), "featScroll", DataType.number(),
        "isGeneral", DataType.bool(), "isWeapons", DataType.bool(), "wtpHint", DataType.string());
    static final DataType.ListOf BACKGROUNDS = DataType.list(DataType.object("id", DataType.string(),
        "name", DataType.string(), "description", DataType.string(), "bonus", DataType.string(),
        "selected", DataType.bool()), "id");
    static final DataType.ListOf CLASSES = DataType.list(DataType.object("id", DataType.string(),
        "index", DataType.integer(), "name", DataType.string(), "icon", ICON, "hasIcon", DataType.bool(),
        "selected", DataType.bool()), "id");
    static final DataType.ListOf ABILITIES = DataType.list(DataType.object("key", DataType.string(),
        "index", DataType.integer(), "name", DataType.string(), "description", DataType.string(), "icon", ICON,
        "hasIcon", DataType.bool(), "label", DataType.string(), "canSpend", DataType.bool(),
        "rule", DataType.bool()), "key");
    static final DataType.ListOf SKILLS = DataType.list(DataType.object("id", DataType.string(),
        "name", DataType.string(), "level", DataType.string(), "canInvest", DataType.bool()), "id");
    static final DataType.ListOf WEAPONS = DataType.list(DataType.object("id", DataType.string(),
        "name", DataType.string(), "rank", DataType.string(), "note", DataType.string(), "gated", DataType.bool(),
        "canTrain", DataType.bool()), "id");
    static final DataType.ListOf FEATS = DataType.list(DataType.object("id", DataType.string(),
        "name", DataType.string(), "description", DataType.string(), "acquired", DataType.bool(),
        "canAfford", DataType.bool(), "rule", DataType.bool()), "id");
    static final DataType.ListOf COSMETICS = DataType.list(DataType.object("id", DataType.string(),
        "name", DataType.string(), "equipped", DataType.bool(), "rule", DataType.bool()), "id");

    /** The game behind the screen. */
    public interface Services {
        /** Character creation while it is the current screen, or null. */
        default CharacterCreationScreen characterCreation() {
            return null;
        }

        /** The window in device px ({@code {w, h}}); null: unknown. */
        default int[] screenSize() {
            return null;
        }
    }

    private final Services services;
    private final DataCell state;
    private final Map<String, DataCollection> lists = new LinkedHashMap<>();
    private final Map<String, List<UiValue>> last = new LinkedHashMap<>();
    private UiValue lastState;

    public CharacterCreationContracts(UiHost ui, Services services) {
        this.services = services;
        state = ui.data().register("characterCreation", new DataCell(STATE, state(null, 1920, 1080)), CREATION);
        list(ui, "ccBackgrounds", BACKGROUNDS);
        list(ui, "ccClasses", CLASSES);
        list(ui, "ccAbilities", ABILITIES);
        list(ui, "ccSkills", SKILLS);
        list(ui, "ccWeapons", WEAPONS);
        list(ui, "ccFeats", FEATS);
        list(ui, "ccHair", COSMETICS);
        list(ui, "ccHats", COSMETICS);
        DataType.Obj id = DataType.object("id", DataType.string());
        action(ui, "tab", DataType.object("tab", DataType.string()), (c, a) -> {
            try {
                c.state().setActiveTab(CharacterCreationTab.valueOf(str(a, "tab")));
                return null;
            } catch (IllegalArgumentException e) {
                return "no such tab";
            }
        });
        action(ui, "talent", DataType.object("sub", DataType.string()), (c, a) -> {
            try {
                c.state().setTalentSubTab(TalentSubTab.valueOf(str(a, "sub")));
                return null;
            } catch (IllegalArgumentException e) {
                return "no such talent tab";
            }
        });
        action(ui, "nav", DataType.object("name", DataType.string()), (c, a) -> switch (str(a, "name")) {
            case "back" -> {
                c.state().getBackToWorldSelectButton().click();
                yield null;
            }
            case "next" -> {
                c.state().getTerrainMapperButton().click();
                yield null;
            }
            case "undo" -> {
                c.state().getUndoButton().click(); // a disabled Undo (nothing to undo) takes the press, does nothing
                yield null;
            }
            default -> "no such button";
        });
        action(ui, "background", id, (c, a) -> {
            c.actions().onSelectBackground(str(a, "id"));
            return null;
        });
        action(ui, "ability", DataType.object("index", DataType.number(), "delta", DataType.number()), (c, a) -> {
            int i = (int) num(a, "index");
            if (i < 0 || i >= 6) {
                return "no such ability";
            }
            if (num(a, "delta") > 0) {
                c.actions().onAbilityIncrement(i);
            } else {
                c.actions().onAbilityDecrement(i);
            }
            return null;
        });
        action(ui, "class", id, (c, a) -> {
            for (int i = 0; i < ClassRegistry.ALL.size(); i++) {
                if (ClassRegistry.ALL.get(i).id().equals(str(a, "id"))) {
                    c.actions().onSelectClass(i);
                    return null;
                }
            }
            return "no such class";
        });
        action(ui, "spend", DataType.object("index", DataType.number()), (c, a) -> {
            int sel = c.state().getSelectedClassIndex();
            if (sel < 0 || sel >= ClassRegistry.ALL.size() || c.getCharacterStats().getRemainingCp() <= 0) {
                return null; // no class or no CP: no spend button takes the press
            }
            c.actions().onSpendCp(ClassRegistry.ALL.get(sel).id() + ":" + (int) num(a, "index"));
            return null;
        });
        action(ui, "skill", id, (c, a) -> {
            if (c.getCharacterStats().getRemainingSkillPoints() > 0) {
                c.actions().onInvestSkill(str(a, "id"));
            }
            return null;
        });
        action(ui, "skill-mode", DataType.object("mode", DataType.string()), (c, a) -> {
            switch (str(a, "mode")) {
                case "general" -> c.actions().onSelectSkillMode(SkillListMode.GENERAL);
                case "weapons" -> c.actions().onSelectSkillMode(SkillListMode.WEAPONS);
                default -> {
                    return "no such skill list";
                }
            }
            return null;
        });
        action(ui, "train", id, (c, a) -> {
            WeaponGroup group = WeaponTraining.group(str(a, "id"));
            if (group == null) {
                return "no such weapon group";
            }
            c.actions().onTrainWeapon(group);
            return null;
        });
        action(ui, "feat", id, (c, a) -> {
            CharacterStats s = c.getCharacterStats();
            if (s.getRemainingFeatPoints() > 0 && !s.hasFeat(str(a, "id"))) {
                c.actions().onAcquireFeat(str(a, "id"));
            }
            return null;
        });
        action(ui, "hair", id, (c, a) -> {
            if (!str(a, "id").equalsIgnoreCase(PlayerLooks.getSelectedHairId())) {
                c.actions().onSelectHair(str(a, "id"));
            }
            return null;
        });
        action(ui, "hat", id, (c, a) -> {
            if (!str(a, "id").equalsIgnoreCase(PlayerLooks.getSelectedHatId())) {
                c.actions().onSelectHat(str(a, "id"));
            }
            return null;
        });
        action(ui, "wheel", DataType.object("dy", DataType.number()), (c, a) -> {
            c.mouse().handleMouseWheel(0, 0, num(a, "dy"));
            return null;
        });
    }

    /** UI thread, once per frame (the scrolls are clamped here). */
    public void poll() {
        CharacterCreationScreen c = services.characterCreation();
        int[] size = services.screenSize();
        int w = size == null ? 1920 : size[0];
        int h = size == null ? 1080 : size[1];
        if (c != null) {
            c.updateLabelsForMode();
            c.clampScrolls();
        }
        UiValue s = state(c, w, h);
        if (!s.equals(lastState)) {
            lastState = s;
            state.set(s);
        }
        CharacterStats stats = c == null ? null : c.getCharacterStats();
        publish("ccBackgrounds", backgrounds(stats));
        publish("ccClasses", classes(c));
        publish("ccAbilities", abilities(c));
        publish("ccSkills", skills(stats));
        publish("ccWeapons", weapons(stats));
        publish("ccFeats", feats(stats));
        publish("ccHair", cosmetics(PlayerLooks.HAIR_OPTIONS, PlayerLooks.getSelectedHairId()));
        publish("ccHats", cosmetics(PlayerLooks.HAT_OPTIONS, PlayerLooks.getSelectedHatId()));
    }

    static UiValue state(CharacterCreationScreen c, int w, int h) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        CharacterCreationStateManager st = c == null ? null : c.state();
        CharacterStats stats = st == null ? new CharacterStats(null) : st.getCharacterStats();
        CharacterCreationTab active = st == null ? CharacterCreationTab.BACKGROUND : st.getActiveTab();
        m.put("tab", UiValue.of(active.name()));
        TalentSubTab sub = st == null ? TalentSubTab.CLASS_ABILITIES : st.getTalentSubTab();
        m.put("pane", UiValue.of(active == CharacterCreationTab.TALENTS ? sub.name() : active.name()));

        CharacterCreationLayout layout = c == null ? new CharacterCreationLayout() : c.layout();
        CharacterCreationLayout.Rect left = layout.leftPanel(w, h);
        CharacterCreationLayout.Rect right = layout.rightPanel(w, h);
        CharacterCreationLayout.Rect tabBar = layout.tabBar(right);
        CharacterCreationLayout.Rect content = layout.tabContent(right, tabBar);
        CharacterCreationLayout.PreviewRect preview = CharacterCreationLayout.previewRect(left.x(),
            left.y(), left.width(), left.height());
        Map<String, UiValue> l = new LinkedHashMap<>();
        l.put("left", rect(left));
        l.put("right", rect(right));
        l.put("footer", rect(layout.footer(w, h)));
        l.put("tabBar", rect(tabBar));
        l.put("content", rect(content));
        // the Talents tab: its sub-tab bar 8 px into the content, 30 high; the sub-tab's pane 6 px under it
        CharacterCreationLayout.Rect bar = new CharacterCreationLayout.Rect(content.x() + TALENT_BAR_PAD,
            content.y() + TALENT_BAR_PAD, content.width() - TALENT_BAR_PAD * 2f, TALENT_BAR_H);
        float subY = bar.bottom() + TALENT_CONTENT_GAP;
        l.put("talentBar", rect(bar));
        l.put("talentContent", rect(content.x(), subY, content.width(), content.bottom() - subY));
        l.put("preview", preview == null ? rect(0, 0, 0, 0) : rect(preview.x(), preview.y(), preview.w(), preview.h()));
        l.put("hasPreview", UiValue.of(preview != null));
        // the ability tiles' centred 3 x 2 grid, the skills' level column (relative offsets)
        l.put("gridX", UiValue.of((content.width() - (3 * 90f + 2 * 12f)) / 2f));
        l.put("lvlX", UiValue.of((content.width() - 16f * 2f) * 0.55f));
        m.put("layout", new UiValue.Obj(l));

        List<UiValue> tabs = new ArrayList<>();
        TabMetric[] metrics = c == null ? null : c.tabMetrics(w, h);
        CharacterCreationTab[] all = CharacterCreationTab.values();
        for (int i = 0; i < all.length; i++) {
            Map<String, UiValue> t = new LinkedHashMap<>();
            t.put("tab", UiValue.of(all[i].name()));
            t.put("label", UiValue.of(all[i].displayName()));
            t.put("x", UiValue.of(metrics == null ? 0 : metrics[i].x()));
            t.put("width", UiValue.of(metrics == null ? 100 : metrics[i].width()));
            t.put("fontSize", UiValue.of(metrics == null ? 14 : metrics[i].fontSize()));
            t.put("baseline", UiValue.of(metrics == null ? 23.32 : metrics[i].baseline()));
            t.put("active", UiValue.of(all[i] == active));
            tabs.add(new UiValue.Obj(t));
        }
        m.put("tabs", new UiValue.Arr(tabs));
        float slotW = bar.width() / TALENT_LABELS.length;
        List<UiValue> talents = new ArrayList<>();
        for (int i = 0; i < TALENT_LABELS.length; i++) {
            Map<String, UiValue> t = new LinkedHashMap<>();
            t.put("sub", UiValue.of(TalentSubTab.values()[i].name()));
            t.put("label", UiValue.of(TALENT_LABELS[i]));
            t.put("x", UiValue.of(i * slotW));
            t.put("w", UiValue.of(slotW - (i < TALENT_LABELS.length - 1 ? 2f : 0f)));
            t.put("selected", UiValue.of(TalentSubTab.values()[i] == sub));
            talents.add(new UiValue.Obj(t));
        }
        m.put("talents", new UiValue.Arr(talents));
        m.put("talentBaseline", UiValue.of(TALENT_BAR_H / 2f + com.openmason.engine.ui.masonry.MStyle.FONT_META * 0.35f));
        m.put("undoDisabled", UiValue.of(!stats.canUndo()));
        m.put("backLabel", UiValue.of(st == null ? "World Select" : st.getBackToWorldSelectButton().text()));
        m.put("nextLabel", UiValue.of(st == null ? "Terrain Mapper" : st.getTerrainMapperButton().text()));

        m.put("classLabel", UiValue.of("Class: " + stats.getCharacterClass()));
        String bgId = stats.getSelectedBackground();
        m.put("backgroundLabel", UiValue.of("Background: "
            + (bgId != null ? capitalize(bgId) : "No Background")));
        m.put("ap", UiValue.of("AP Remaining: " + stats.getRemainingAp()));
        m.put("cp", UiValue.of("CP Remaining: " + stats.getRemainingCp()));
        // the Skills pane's header follows its list: SP for general skills, the Weapon Training grant for weapons
        boolean weapons = st != null && st.getSkillMode() == SkillListMode.WEAPONS;
        m.put("sp", UiValue.of(weapons ? "WTP Remaining: " + stats.getRemainingWtp()
            : "SP Remaining: " + stats.getRemainingSkillPoints() + "    WTP: " + stats.getRemainingWtp()));
        m.put("fp", UiValue.of("FP Remaining: " + stats.getRemainingFeatPoints()));

        int sel = st == null ? -1 : st.getSelectedClassIndex();
        PlayerClassDefinition cls = sel >= 0 && sel < ClassRegistry.ALL.size() ? ClassRegistry.ALL.get(sel) : null;
        m.put("className", UiValue.of(cls == null ? "Select a class" : cls.name()));
        m.put("classDesc", UiValue.of(cls == null ? "Click a class on the left to view its abilities."
            : cls.description()));
        m.put("noClass", UiValue.of(cls == null));

        int[] scores = stats.getAbilityScores();
        int[] bonuses = new int[6];
        BackgroundRegistry.findById(bgId).ifPresent(bg -> System.arraycopy(bg.abilityScoreBonuses(), 0, bonuses, 0, 6));
        List<UiValue> tiles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Map<String, UiValue> t = new LinkedHashMap<>();
            t.put("tile", UiValue.of("t" + i));
            t.put("abbrev", UiValue.of(ABBREV[i]));
            t.put("score", UiValue.of(String.valueOf(scores[i])));
            int mod = stats.getModifier(Math.max(1, scores[i] + bonuses[i]));
            t.put("mod", UiValue.of((mod >= 0 ? "+" : "") + mod));
            t.put("bonus", UiValue.of(bonuses[i] == 0 ? "" : (bonuses[i] > 0 ? "+" : "-") + Math.abs(bonuses[i])));
            t.put("bonusUp", UiValue.of(bonuses[i] > 0));
            t.put("minusEnabled", UiValue.of(scores[i] > 1));
            t.put("plusEnabled", UiValue.of(stats.getRemainingAp() > 0));
            tiles.add(new UiValue.Obj(t));
        }
        m.put("tiles", new UiValue.Arr(tiles));
        m.put("classScroll", UiValue.of(st == null ? 0 : st.getClassScroll()));
        m.put("skillScroll", UiValue.of(st == null ? 0 : st.getSkillScroll()));
        m.put("isGeneral", UiValue.of(!weapons));
        m.put("isWeapons", UiValue.of(weapons));
        m.put("wtpHint", UiValue.of(WeaponTraining.hint()));
        m.put("featScroll", UiValue.of(st == null ? 0 : st.getFeatScroll()));
        return new UiValue.Obj(m);
    }

    private static List<UiValue> backgrounds(CharacterStats stats) {
        List<UiValue> out = new ArrayList<>();
        for (BackgroundDefinition bg : BackgroundRegistry.ALL) {
            out.add(obj("id", bg.id(), "name", bg.name(), "description", bg.description(),
                "bonus", formatBonusLine(bg.abilityScoreBonuses()),
                "selected", stats != null && bg.id().equals(stats.getSelectedBackground())));
        }
        return out;
    }

    private static List<UiValue> classes(CharacterCreationScreen c) {
        int sel = c == null ? -1 : c.state().getSelectedClassIndex();
        List<UiValue> out = new ArrayList<>();
        for (int i = 0; i < ClassRegistry.ALL.size(); i++) {
            PlayerClassDefinition cls = ClassRegistry.ALL.get(i);
            out.add(obj("id", cls.id(), "index", i, "name", cls.name(), "icon", icon(cls.iconPath()),
                "hasIcon", cls.iconPath() != null, "selected", i == sel));
        }
        return out;
    }

    private static List<UiValue> abilities(CharacterCreationScreen c) {
        int sel = c == null ? -1 : c.state().getSelectedClassIndex();
        if (sel < 0 || sel >= ClassRegistry.ALL.size()) {
            return List.of();
        }
        CharacterStats stats = c.getCharacterStats();
        PlayerClassDefinition cls = ClassRegistry.ALL.get(sel);
        List<ClassAbility> abilities = cls.abilities();
        List<UiValue> out = new ArrayList<>();
        for (int i = 0; i < abilities.size(); i++) {
            ClassAbility a = abilities.get(i);
            String key = ClassRegistry.coreKey(cls.id(), i);
            out.add(obj("key", key, "index", i, "name", a.name(), "description", a.description(),
                "icon", icon(a.iconPath()), "hasIcon", a.iconPath() != null,
                "label", "Spend " + stats.getCpCostPerRank(cls.id()) + " CP (" + stats.getRank(key) + ")",
                "canSpend", stats.canAddRank(key),
                "rule", i < abilities.size() - 1));
        }
        return out;
    }

    private static List<UiValue> skills(CharacterStats stats) {
        List<UiValue> out = new ArrayList<>();
        for (SkillDefinition s : SkillRegistry.ALL) {
            out.add(obj("id", s.id(), "name", s.name(), "level", "Lvl: " + (stats == null ? 0 : stats.getSkillLevel(s.id())),
                "canInvest", stats != null && stats.getRemainingSkillPoints() > 0));
        }
        return out;
    }

    private static List<UiValue> weapons(CharacterStats stats) {
        List<UiValue> out = new ArrayList<>();
        if (stats == null) {
            return out;
        }
        for (WeaponTraining.Row w : WeaponTraining.rows(stats)) {
            out.add(obj("id", w.group().name(), "name", w.name(), "rank", w.rank(), "note", w.note(),
                "gated", w.gated(), "canTrain", w.canTrain()));
        }
        return out;
    }

    private static List<UiValue> feats(CharacterStats stats) {
        List<FeatDefinition> all = FeatRegistry.ALL;
        List<UiValue> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            FeatDefinition f = all.get(i);
            out.add(obj("id", f.id(), "name", f.name(), "description", f.description(),
                "acquired", stats != null && stats.hasFeat(f.id()),
                "canAfford", stats != null && stats.getRemainingFeatPoints() > 0, "rule", i < all.size() - 1));
        }
        return out;
    }

    private static List<UiValue> cosmetics(List<PlayerLooks.CosmeticOption> options, String selected) {
        List<UiValue> out = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            PlayerLooks.CosmeticOption o = options.get(i);
            out.add(obj("id", o.id(), "name", o.displayName(), "equipped", o.id().equalsIgnoreCase(selected),
                "rule", i < options.size() - 1));
        }
        return out;
    }

    /** "No Background" style capitalisation. */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The bonus line of a background row. */
    private static String formatBonusLine(int[] bonuses) {
        if (bonuses == null) return "No stat changes";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6 && i < bonuses.length; i++) {
            if (bonuses[i] == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(ABBREV[i]).append(' ');
            if (bonuses[i] > 0) sb.append('+');
            sb.append(bonuses[i]);
        }
        return sb.length() == 0 ? "No stat changes" : sb.toString();
    }

    private static UiValue icon(String path) {
        return new UiValue.Obj(Map.of("path", UiValue.of(path == null ? "" : path)));
    }

    private static UiValue rect(CharacterCreationLayout.Rect r) {
        return rect(r.x(), r.y(), r.width(), r.height());
    }

    private static UiValue rect(float x, float y, float w, float h) {
        return new UiValue.Arr(List.of(UiValue.of(x), UiValue.of(y), UiValue.of(w), UiValue.of(h)));
    }

    private static UiValue obj(Object... kv) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], switch (v) {
                case UiValue u -> u;
                case String s -> UiValue.of(s);
                case Boolean b -> UiValue.of(b);
                case Integer n -> UiValue.of(n);
                default -> throw new IllegalArgumentException(String.valueOf(v));
            });
        }
        return new UiValue.Obj(m);
    }

    private void list(UiHost ui, String name, DataType.ListOf type) {
        DataCollection c = new DataCollection(type);
        lists.put(name, c);
        ui.data().register(name, c, CREATION);
    }

    private void publish(String name, List<UiValue> rows) {
        if (!rows.equals(last.get(name))) {
            last.put(name, rows);
            lists.get(name).setAll(rows);
        }
    }

    @FunctionalInterface
    private interface Rule {
        String apply(CharacterCreationScreen screen, UiValue.Obj args);
    }

    private void action(UiHost ui, String name, DataType.Obj params, Rule rule) {
        ui.actions().register(ActionSpec.of(CREATION.id() + "." + name, CREATION, params, DataType.ANY), (args, ctx) -> {
            CharacterCreationScreen c = services.characterCreation();
            String problem = c == null ? "character creation is not showing"
                : rule.apply(c, args instanceof UiValue.Obj o ? o : new UiValue.Obj(Map.of()));
            poll();
            return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
                : CompletableFuture.failedFuture(new IllegalStateException(problem));
        });
    }

    private static String str(UiValue.Obj a, String k) {
        return a.get(k) instanceof UiValue.Str s ? s.value() : "";
    }

    private static double num(UiValue.Obj a, String k) {
        return a.get(k) instanceof UiValue.Num n ? n.value() : 0;
    }
}
