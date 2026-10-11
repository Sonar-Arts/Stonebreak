package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.CharacterPanelTab;
import com.stonebreak.rpg.classes.ClassAbility;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.classes.PlayerClassDefinition;
import com.stonebreak.rpg.feats.FeatDefinition;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.rpg.skills.SkillDefinition;
import com.stonebreak.rpg.skills.SkillRegistry;
import com.stonebreak.ui.TabStripLayout;
import com.stonebreak.ui.characterScreen.CharacterController;
import com.stonebreak.ui.characterScreen.CharacterScreen;
import com.stonebreak.ui.characterScreen.ClassesTab;
import com.stonebreak.ui.characterScreen.FeatsTab;
import io.github.humbleui.skija.Font;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The in-game character sheet ({@code stonebreak:screen.character-sheet} 1, #301). {@link CharacterScreen}'s
 * controller and its tab states are the sheet's state (stats, the class shown, scrolls, feat filters and selection);
 * the document shows them. One root {@code characterSheet}: the active {@code tab}, the {@code panel} and the shared
 * five-tab strip in window pixels, and one record per tab with the sheet's derived values (a scaled column split,
 * wrapped description lines, the dirt pane, scrollbars, labels) — offsets that are plain multiples of the UI scale are
 * the document's own.
 *
 * <p>Actions: {@code .tab {tab}} (inventory, character, classes, skills, feats), {@code .ability {index, delta}},
 * {@code .class {id}}, {@code .spend {index}}, {@code .skill {index}}, {@code .level-filter {direction}},
 * {@code .general}, {@code .feat {id}}, {@code .acquire}, {@code .wheel {dy, sidebar}}; each refuses unless the sheet
 * is showing and runs that control's rule.
 */
public final class CharacterSheetContracts {

    public static final HostContract SHEET = HostContract.of("stonebreak:screen.character-sheet", 1);

    private static final String[] ABBREV = {"STR", "DEX", "CON", "INT", "WIS", "CHA"};
    private static final String[] TAB_KEYS = {"inventory", "character", "classes", "skills", "feats"};
    private static final String[] TAB_LABELS = {"Inventory", "Character", "Classes", "Skills", "Feats"};

    /** The game behind the sheet. */
    public interface Services {
        /** The character sheet while it is open, or null. */
        default CharacterScreen characterSheet() {
            return null;
        }

        /** The window in device px ({@code {w, h}}); null: unknown. */
        default int[] screenSize() {
            return null;
        }
    }

    private final Services services;
    private final DataCell state;
    private UiValue last;

    public CharacterSheetContracts(UiHost ui, Services services) {
        this.services = services;
        state = ui.data().register("characterSheet", new DataCell(DataType.ANY, closed()), SHEET);
        DataType.Obj id = DataType.object("id", DataType.string());
        DataType.Obj index = DataType.object("index", DataType.number());
        action(ui, "tab", DataType.object("tab", DataType.string()), (c, a) -> {
            var controller = c.getController();
            switch (str(a, "tab")) {
                case "inventory" -> c.getController().openInventory();
                case "character" -> controller.setActiveTab(CharacterPanelTab.OVERVIEW);
                case "classes" -> controller.setActiveTab(CharacterPanelTab.CLASSES);
                case "skills" -> controller.setActiveTab(CharacterPanelTab.SKILLS);
                case "feats" -> controller.setActiveTab(CharacterPanelTab.FEATS);
                default -> {
                    return "no such tab";
                }
            }
            return null;
        });
        action(ui, "ability", DataType.object("index", DataType.number(), "delta", DataType.number()), (c, a) -> {
            c.getController().pressAbility((int) num(a, "index"), (int) Math.signum(num(a, "delta")));
            return null;
        });
        action(ui, "class", id, (c, a) -> {
            c.getController().classes().selectClass(str(a, "id"));
            return null;
        });
        action(ui, "spend", index, (c, a) -> {
            c.getController().classes().spend(c.getController().stats(), (int) num(a, "index"));
            return null;
        });
        action(ui, "skill", index, (c, a) -> {
            c.getController().skills().invest(c.getController().stats(), (int) num(a, "index"));
            return null;
        });
        action(ui, "level-filter", DataType.object("direction", DataType.number()), (c, a) -> {
            c.getController().feats().cycleLevel(num(a, "direction") < 0 ? -1 : 1);
            return null;
        });
        action(ui, "general", DataType.object(), (c, a) -> {
            c.getController().feats().toggleGeneral();
            return null;
        });
        action(ui, "feat", id, (c, a) -> {
            c.getController().feats().selectFeat(str(a, "id"));
            return null;
        });
        action(ui, "acquire", DataType.object(), (c, a) -> {
            c.getController().feats().acquire(c.getController().stats());
            return null;
        });
        action(ui, "wheel", DataType.object("dy", DataType.number(), "sidebar", DataType.bool()), (c, a) -> {
            float s = com.stonebreak.config.Settings.getInstance().getUiScale();
            float dy = (float) num(a, "dy");
            switch (c.getController().getActiveTab()) {
                case CLASSES -> c.getController().classes().scroll(dy, a.get("sidebar") instanceof UiValue.Bool b
                    && b.value(), s);
                case FEATS -> c.getController().feats().handleScroll(dy, s);
                default -> {
                }
            }
            return null;
        });
    }

    /** UI thread, once per frame and right before the sheet's document paints. */
    public void poll() {
        CharacterScreen c = services.characterSheet();
        int[] size = services.screenSize();
        UiValue v = c == null ? closed() : model(c, size == null ? 1920 : size[0], size == null ? 1080 : size[1]);
        if (!v.equals(last)) {
            last = v;
            state.set(v);
        }
    }

    private static UiValue closed() {
        return obj("tab", "OVERVIEW", "open", false);
    }

    // ── the sheet's geometry ──

    static UiValue model(CharacterScreen screen, int w, int h) {
        CharacterController rc = screen.getController();
        CharacterStats stats = rc.stats();
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        float[] p = CharacterController.panel(w, h, s);
        Map<String, UiValue> m = new LinkedHashMap<>();
        CharacterPanelTab tab = screen.getController().getActiveTab();
        m.put("tab", UiValue.of(tab.name()));
        m.put("open", UiValue.of(true));
        m.put("panel", rect(p[0], p[1], p[2], p[3]));

        int tabW = TabStripLayout.tabWidth();
        int tabH = TabStripLayout.tabHeight();
        int tabY = TabStripLayout.tabY(Math.round(p[1]));
        int startX = TabStripLayout.startX(w);
        int stride = TabStripLayout.stride();
        int activeIndex = switch (tab) {
            case OVERVIEW -> 1;
            case CLASSES -> 2;
            case SKILLS -> 3;
            case FEATS -> 4;
        };
        List<UiValue> tabs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, UiValue> t = new LinkedHashMap<>();
            t.put("tab", UiValue.of(TAB_KEYS[i]));
            t.put("label", UiValue.of(TAB_LABELS[i]));
            t.put("rect", rect(startX + stride * i, tabY, tabW, tabH));
            t.put("active", UiValue.of(i == activeIndex));
            tabs.add(new UiValue.Obj(t));
        }
        m.put("tabs", new UiValue.Arr(tabs));
        m.put("tabBaseline", UiValue.of(tabH * 0.5f + MStyle.FONT_META * 0.38f * s));

        Font meta = rc.fonts().getScaled(MStyle.FONT_META);
        switch (tab) {
            case OVERVIEW -> m.put("overview", overview(stats, p, s, meta));
            case CLASSES -> m.put("classes", classes(rc.classes(), stats, p, s, meta));
            case SKILLS -> m.put("skills", skills(stats, s));
            case FEATS -> m.put("feats", feats(rc.feats(), stats, p, s));
        }
        return new UiValue.Obj(m);
    }

    private static UiValue overview(CharacterStats stats, float[] p, float s, Font meta) {
        Map<String, UiValue> o = new LinkedHashMap<>();
        float pw = p[2];
        float leftColW = pw * 0.38f;
        float leftColX = p[0] + 16 * s;
        float rightColX = leftColX + leftColW + 12f * s;
        float rightColW = pw - leftColW - 44f * s;
        float contentY = p[1] + 20f * s;
        o.put("left", rect(leftColX, contentY, leftColW, 170f * s));
        o.put("rightX", UiValue.of(rightColX));
        o.put("rightW", UiValue.of(rightColW));
        o.put("contentY", UiValue.of(contentY));
        o.put("classLine", UiValue.of("Class: " + stats.getCharacterClass()));
        List<String> feats = stats.getFeats();
        List<UiValue> featLines = new ArrayList<>();
        if (feats.isEmpty()) {
            featLines.add(obj("line", "0", "text", "(None)", "dim", true));
        } else {
            for (int i = 0; i < Math.min(feats.size(), 6); i++) {
                featLines.add(obj("line", String.valueOf(i), "text", feats.get(i), "dim", false));
            }
            if (feats.size() > 6) {
                featLines.add(obj("line", "more", "text", "+" + (feats.size() - 6) + " more…", "dim", true));
            }
        }
        o.put("featLines", new UiValue.Arr(featLines));
        o.put("currencies", new UiValue.Arr(List.of(
            obj("line", "cp", "text", "CP: " + stats.getRemainingCp()),
            obj("line", "sp", "text", "SP: " + stats.getRemainingSkillPoints()),
            obj("line", "fp", "text", "FP: " + stats.getRemainingFeatPoints()),
            obj("line", "ap", "text", "AP: " + stats.getRemainingAp()))));
        // the level widget
        int xp = stats.getXp();
        int xpMax = stats.getXpForNextLevel();
        float fraction = xpMax > 0 ? Math.min(1f, (float) xp / xpMax) : 0f;
        float barH = Math.round(10 * s);
        float barY = contentY + MStyle.FONT_META * s + 4f * s;
        o.put("levelText", UiValue.of("Level " + stats.getLevel()));
        o.put("levelBaseline", UiValue.of(contentY + MStyle.FONT_META * 0.85f * s));
        o.put("bar", rect(rightColX, barY, rightColW, barH));
        o.put("barFill", UiValue.of(fraction > 0f ? rightColW * fraction : 0f));
        String xpText = xp + " / " + xpMax + " xp";
        o.put("xpText", UiValue.of(xpText));
        o.put("xpX", UiValue.of(rightColX + rightColW - MPainter.measureWidth(meta, xpText)));
        float abilitiesY = barY + barH + 6f * s;
        o.put("abilitiesY", UiValue.of(abilitiesY));
        int[] scores = stats.getAbilityScores();
        List<UiValue> tiles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int mod = stats.getModifier(scores[i]);
            tiles.add(obj("tile", "t" + i, "abbrev", ABBREV[i], "score", String.valueOf(scores[i]),
                "mod", (mod >= 0 ? "+" : "") + mod, "minusEnabled", scores[i] > 1,
                "plusEnabled", stats.getRemainingAp() > 0));
        }
        o.put("tiles", new UiValue.Arr(tiles));
        float barSectionY = abilitiesY + 190f * s;
        float hp = stats.getHealth();
        float maxHp = stats.getMaxHealth();
        o.put("barsY", UiValue.of(barSectionY));
        o.put("hearts", obj("shape", "heart", "count", 10, "filled", maxHp > 0f ? (hp / maxHp) * 10f : 0f,
            "size", 13, "gap", 2, "color", "#CC2222FF", "dim", "#551111FF"));
        o.put("hpText", UiValue.of((int) hp + "/" + (int) maxHp));
        o.put("mana", obj("shape", "diamond", "count", 10, "filled", 0, "size", 11, "gap", 2,
            "color", "#334466FF", "dim", "#334466FF"));
        o.put("slotSize", UiValue.of(Math.round(34 * s)));
        return new UiValue.Obj(o);
    }

    private static UiValue classes(ClassesTab r, CharacterStats stats, float[] p, float s, Font meta) {
        Map<String, UiValue> o = new LinkedHashMap<>();
        String shown = r.displayedClassId();
        float btnH = 22f * s;
        float btnG = 2f * s;
        float clipH = 420f * s;
        List<UiValue> rows = new ArrayList<>();
        for (PlayerClassDefinition cls : ClassRegistry.ALL) {
            int cp = cls.id() == null ? 0 : stats.getTotalCpSpentForClass(cls.id());
            rows.add(obj("id", cls.id(), "name", cls.name(), "icon", obj("path", cls.iconPath() == null ? ""
                : cls.iconPath()), "hasIcon", cls.iconPath() != null, "selected", cls.id().equals(shown),
                "hasPoints", cp > 0, "cp", cp > 0 ? cp + " CP" : ""));
        }
        o.put("rows", new UiValue.Arr(rows));
        o.put("leftScroll", UiValue.of(r.leftScroll()));
        o.put("leftBar", scrollbar(30f * s + 20f * s, clipH, r.leftScroll(), ClassRegistry.ALL.size() * (btnH + btnG)));

        Optional<PlayerClassDefinition> cls = shown == null ? Optional.empty() : ClassRegistry.findById(shown);
        float cw = 391f * s;
        float cy = 20f * s; // relative to the panel's top
        o.put("className", UiValue.of(cls.map(PlayerClassDefinition::name).orElse("Select a class")));
        List<String> desc = ClassesTab.wrap(meta, cls.map(PlayerClassDefinition::description)
            .orElse("Choose a class from the list on the left."), cw - 8f);
        List<UiValue> lines = new ArrayList<>();
        for (int i = 0; i < desc.size(); i++) {
            lines.add(obj("line", String.valueOf(i), "text", desc.get(i)));
        }
        o.put("descLines", new UiValue.Arr(lines));
        float descY = cy + 36f * s;
        float lastBaseline = descY + Math.max(0, desc.size() - 1) * 16f * s;
        float ruleY = lastBaseline + 10f * s;
        float dirtY = ruleY + 6f * s;
        float dirtH = Math.max(150f * s, cy + 452f * s - dirtY);
        o.put("ruleY", UiValue.of(ruleY));
        o.put("dirtY", UiValue.of(dirtY));
        o.put("dirtH", UiValue.of(dirtH));
        float clip2 = Math.max(0f, dirtH - 35f * s);
        r.abilityClipHeight(clip2); // the scroll clamp's view height
        o.put("abilityClipH", UiValue.of(clip2));
        o.put("cp", UiValue.of("CP Available: " + stats.getRemainingCp()));
        List<UiValue> abilities = new ArrayList<>();
        if (cls.isPresent()) {
            List<ClassAbility> list = cls.get().abilities();
            for (int i = 0; i < list.size(); i++) {
                ClassAbility a = list.get(i);
                abilities.add(obj("key", cls.get().id() + ":" + i, "name", a.name(), "description", a.description(),
                    "icon", obj("path", a.iconPath() == null ? "" : a.iconPath()), "hasIcon", a.iconPath() != null,
                    "label", "Spend " + a.cpCost() + " CP (" + stats.getSpentCp(cls.get().id() + ":" + i) + ")",
                    "canSpend", stats.getRemainingCp() > 0, "rule", i < list.size() - 1));
            }
            o.put("rightBar", scrollbar(dirtY + 28f * s, clip2, r.rightScroll(), list.size() * 64f * s));
        } else {
            o.put("rightBar", scrollbar(0, 0, 0, 0));
        }
        o.put("abilities", new UiValue.Arr(abilities));
        o.put("rightScroll", UiValue.of(r.rightScroll()));
        return new UiValue.Obj(o);
    }

    private static UiValue skills(CharacterStats stats, float s) {
        Map<String, UiValue> o = new LinkedHashMap<>();
        o.put("sp", UiValue.of("SP Available: " + stats.getRemainingSkillPoints()));
        List<UiValue> rows = new ArrayList<>();
        List<SkillDefinition> all = SkillRegistry.ALL;
        for (int i = 0; i < all.size(); i++) {
            SkillDefinition sk = all.get(i);
            // the hint strip sits at the panel's foot (480 s - 34 s), drawn from this row (48 s + 24 s i)
            rows.add(obj("skill", "s" + i, "name", sk.name(), "level", "Lvl: " + stats.getSkillLevel(sk.id()),
                "description", sk.description(), "canInvest", stats.getRemainingSkillPoints() > 0,
                "tipTop", (480f * s - 34f * s) - (48f * s + i * (24f * s))));
        }
        o.put("rows", new UiValue.Arr(rows));
        return new UiValue.Obj(o);
    }

    private static UiValue feats(FeatsTab r, CharacterStats stats, float[] p, float s) {
        Map<String, UiValue> o = new LinkedHashMap<>();
        o.put("levelLabel", UiValue.of(r.filterLevel() == 0 ? "Level: All" : "Level: " + r.filterLevel()));
        o.put("general", UiValue.of(r.filterGeneral()));
        List<FeatDefinition> list = FeatRegistry.filter(r.filterLevel(), r.filterGeneral());
        List<UiValue> rows = new ArrayList<>();
        for (FeatDefinition f : list) {
            rows.add(obj("id", f.id(), "name", f.name(), "level", "(Lvl " + f.level() + ")", "isGeneral", f.isGeneral(),
                "selected", f.id().equals(r.selectedFeatId())));
        }
        o.put("rows", new UiValue.Arr(rows));
        o.put("scroll", UiValue.of(r.scrollOffset()));
        o.put("bar", scrollbar(58f * s, 414f * s, r.scrollOffset(), list.size() * 24f * s));
        FeatDefinition sel = r.selectedFeatId() == null ? null
            : FeatRegistry.ALL.stream().filter(f -> f.id().equals(r.selectedFeatId())).findFirst().orElse(null);
        o.put("hasSelection", UiValue.of(sel != null));
        boolean acquired = sel != null && stats.hasFeat(sel.id());
        boolean canAfford = stats.getRemainingFeatPoints() > 0;
        o.put("detail", sel == null ? obj("name", "", "type", "", "level", "", "description", "")
            : obj("name", sel.name(), "type", "Type: " + (sel.isGeneral() ? "General Feat" : "Feat"),
                "level", "Required Level: " + sel.level(), "description", sel.description()));
        // the Acquire button: acquired = disabled grey, unaffordable = disabled red, else a live button
        o.put("acquireState", UiValue.of(acquired ? "acquired" : !canAfford ? "poor" : "live"));
        o.put("acquireLabel", UiValue.of(acquired ? "Acquired" : "Acquire? (1 FP)"));
        return new UiValue.Obj(o);
    }

    /** A scrollbar: shown only when the content overflows; thumb at least 20 px. */
    private static UiValue scrollbar(float y, float viewH, float offset, float contentH) {
        boolean shown = contentH > viewH;
        float thumbH = shown ? Math.max(20f, viewH * viewH / contentH) : 0f;
        float thumbY = shown ? y + (offset / contentH) * viewH : 0f;
        return obj("shown", shown, "y", y, "h", viewH, "thumbY", thumbY, "thumbOffset", shown ? thumbY - y : 0f,
            "thumbH", thumbH);
    }

    private static UiValue rect(float x, float y, float w, float h) {
        return obj("x", x, "y", y, "w", w, "h", h);
    }

    private static UiValue obj(Object... kv) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], switch (v) {
                case UiValue u -> u;
                case String str -> UiValue.of(str);
                case Boolean b -> UiValue.of(b);
                case Integer n -> UiValue.of(n);
                case Float f -> UiValue.of(f);
                default -> throw new IllegalArgumentException(String.valueOf(v));
            });
        }
        return new UiValue.Obj(m);
    }

    @FunctionalInterface
    private interface Rule {
        String apply(CharacterScreen screen, UiValue.Obj args);
    }

    private void action(UiHost ui, String name, DataType.Obj params, Rule rule) {
        ui.actions().register(ActionSpec.of(SHEET.id() + "." + name, SHEET, params, DataType.ANY), (args, ctx) -> {
            CharacterScreen c = services.characterSheet();
            String problem = c == null ? "the character sheet is not open"
                : rule.apply(c, args instanceof UiValue.Obj o ? o : new UiValue.Obj(Map.of()));
            poll();
            return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
                : CompletableFuture.failedFuture(new IllegalStateException(problem));
        });
    }

    private static String str(UiValue.Obj a, String k) {
        return a.get(k) instanceof UiValue.Str x ? x.value() : "";
    }

    private static double num(UiValue.Obj a, String k) {
        return a.get(k) instanceof UiValue.Num n ? n.value() : 0;
    }
}
