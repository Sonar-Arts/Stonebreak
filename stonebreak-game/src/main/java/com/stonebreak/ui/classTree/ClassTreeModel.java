package com.stonebreak.ui.classTree;

import com.openmason.engine.format.omui.UiValue;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.rpg.classes.ClassAbility;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.classes.PlayerClassDefinition;
import com.stonebreak.rpg.classes.TalentNode;
import com.stonebreak.rpg.classes.TalentNodeType;
import com.stonebreak.rpg.classes.TalentPath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A class's talent tree laid out Grim Dawn style, shared by character creation's Classes tab and the character
 * sheet's Talents → Classes pane: a mastery bar (the class's ranks, notched at the tier gates), then an icon grid
 * with the core row on top (the always-on passive, then the core abilities, lit once a path that grants them has a
 * rank) and, under the tier headings, one row per path (named with the ability it grants), Tier I to IV left to right, parent → child joined by a connector
 * that lights once the parent has a rank. A detail panel beside the grid (or under it, when the area is narrow)
 * shows the focused node: the hovered one, else the last one clicked, else the core passive.
 *
 * <p>{@link #build} is pure: every coordinate is device px relative to the tree area's top left, the grid's
 * coordinates relative to its (scrollable) clip, the detail's to the area. Node frames and states are class flags
 * so the documents' style sheets own the look.
 */
public final class ClassTreeModel {

    /** Logical px; everything is multiplied by the UI scale. */
    static final float LABEL_W = 86f;
    static final float MIN_COL_W = 60f;
    static final float MAX_COL_W = 120f;
    static final float MASTERY_H = 28f;
    static final float BAR_H = 10f;
    static final float TIER_HEAD_H = 16f;
    /** A node plus the rank pill hanging off its bottom edge, and the gap to the next row. */
    static final float ROW_H = 42f;
    static final float NODE = 32f;
    static final float CAPSTONE = 38f;
    static final float MODIFIER = 26f;
    static final float LINK_H = 3f;
    /** The detail panel: its width beside the grid (at least, at most), its height under it. */
    static final float DETAIL_W = 230f;
    static final float DETAIL_MAX_W = 340f;
    static final float DETAIL_H = 98f;
    static final float GAP = 8f;
    /** Room the scrollbar keeps at the grid's right edge. */
    static final float BAR_ROOM = 8f;

    private static final String[] TIER_NUMERALS = {"I", "II", "III", "IV"};

    private ClassTreeModel() {
    }

    /**
     * The tree of {@code cls} for {@code stats} in an area {@code w} x {@code h} device px at UI scale {@code s}.
     *
     * @param focusKey the node the detail panel describes (a tree key or {@code "<id>:passive"}); null or unknown:
     *                 the core passive
     */
    public static UiValue build(CharacterStats stats, PlayerClassDefinition cls, String focusKey, float s,
                                float w, float h) {
        boolean side = w >= (LABEL_W + 4 * MIN_COL_W + BAR_ROOM + GAP + DETAIL_W) * s;
        float gridW = side ? w - (DETAIL_W + GAP) * s : w;
        float gridH = side ? h : Math.max(0f, h - (DETAIL_H + GAP) * s);
        float colW = Math.clamp((gridW - (LABEL_W + BAR_ROOM) * s) / 4f, MIN_COL_W * s, MAX_COL_W * s);
        float gridX = LABEL_W * s;

        Map<String, UiValue> o = new LinkedHashMap<>();
        o.put("mastery", mastery(stats, cls, gridX, gridX + 4 * colW, s));

        // the core row under the bar, then the tier headings over the path rows (core abilities are all Tier I)
        float coreY = MASTERY_H * s;
        float tierY = coreY + ROW_H * s;
        List<UiValue> tiers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            tiers.add(obj("tier", "t" + t, "label", "Tier " + TIER_NUMERALS[t], "x", gridX + t * colW,
                "y", tierY, "w", colW));
        }
        o.put("tiers", new UiValue.Arr(tiers));

        List<UiValue> nodes = new ArrayList<>();
        List<UiValue> links = new ArrayList<>();
        List<UiValue> rows = new ArrayList<>();
        String focus = focusKey == null ? passiveKey(cls) : focusKey;

        // the core row: the always-on passive, then the core abilities, packed tighter than the tier columns
        float rowY = coreY;
        rows.add(obj("row", "core", "label", "Core", "sub", "", "y", rowY + (NODE / 2f - 14f) * s));
        int coreCount = 1 + cls.abilities().size();
        float coreStride = Math.min(colW, (gridW - gridX - BAR_ROOM * s) / coreCount);
        boolean classActive = stats.getClassRanks(cls.id()) > 0;
        nodes.add(node(passiveKey(cls), "passive", classActive ? "invested" : "innate", cls.corePassive().name(),
            cls.corePassive().iconPath(), "", gridX + coreStride / 2f, rowY, NODE * s, focus, s));
        for (int i = 0; i < cls.abilities().size(); i++) {
            ClassAbility a = cls.abilities().get(i);
            String key = ClassRegistry.coreKey(cls.id(), i);
            // not bought: unlocked by a rank in any path that grants it, so no rank pill
            nodes.add(node(key, "ability", stats.hasAbility(key) ? "invested" : "locked", a.name(), a.iconPath(), "",
                gridX + coreStride * (i + 1.5f), rowY, NODE * s, focus, s));
        }

        // one row per path, a node per tier, each joined to its parent
        for (int p = 0; p < cls.paths().size(); p++) {
            TalentPath path = cls.paths().get(p);
            float y = tierY + TIER_HEAD_H * s + p * ROW_H * s;
            rows.add(obj("row", "p" + p, "label", path.name(), "sub", cls.abilities().get(path.ability()).name(),
                "y", y + (NODE / 2f - 14f) * s));
            for (TalentNode n : path.nodes()) {
                float size = size(n.type()) * s;
                float cx = gridX + (n.tier() + 0.5f) * colW;
                nodes.add(node(n.key(), kind(n.type()), state(stats, n.key()), n.name(), n.iconPath(),
                    badge(stats, n.key()), cx, y, size, focus, s));
                if (n.tier() > 0) {
                    float prevCx = cx - colW;
                    float prevHalf = halfReach(path.nodes().get(n.tier() - 1).type()) * s;
                    float x0 = prevCx + prevHalf;
                    float x1 = cx - halfReach(n.type()) * s;
                    links.add(obj("link", n.key(), "x", x0, "y", y + (NODE / 2f - LINK_H / 2f) * s,
                        "w", Math.max(0f, x1 - x0), "lit", stats.getRank(ClassRegistry.parentKey(n.key())) > 0));
                }
            }
        }
        o.put("rows", new UiValue.Arr(rows));
        o.put("links", new UiValue.Arr(links));
        o.put("nodes", new UiValue.Arr(nodes));

        float contentH = tierY + TIER_HEAD_H * s + cls.paths().size() * ROW_H * s;
        o.put("contentH", UiValue.of(contentH));
        // beside the grid's last column (as tall as the grid, at least DETAIL_H), else under the grid's clip
        float sideX = gridX + 4 * colW + (BAR_ROOM + GAP) * s;
        o.put("clipW", UiValue.of(side ? sideX - GAP * s : gridW));
        o.put("clipH", UiValue.of(gridH));
        o.put("detail", side
            ? detail(stats, cls, focus, sideX, 0f, Math.clamp(w - sideX, DETAIL_W * s, DETAIL_MAX_W * s),
                Math.min(h, Math.max(DETAIL_H * s, contentH)))
            : detail(stats, cls, focus, 0f, gridH + GAP * s, w, DETAIL_H * s));
        return new UiValue.Obj(o);
    }

    /** The tree with no class shown: every list empty, every box zero, so the documents' bindings still resolve. */
    public static UiValue empty() {
        UiValue none = new UiValue.Arr(List.of());
        return obj("mastery", obj("x", 0, "y", 0, "w", 0, "h", 0, "fill", 0, "label", "", "notches", none),
            "tiers", none, "rows", none, "links", none, "nodes", none, "contentH", 0, "clipW", 0, "clipH", 0,
            "detail", obj("x", 0, "y", 0, "w", 0, "h", 0, "name", "", "kind", "", "rank", "", "description", "",
                "status", "", "ok", false));
    }

    /** The key standing for a class's core passive in the grid (not rankable). */
    public static String passiveKey(PlayerClassDefinition cls) {
        return cls.id() + ":passive";
    }

    /** The bar: the class's ranks over the last gate, a notch per gate that lights once that tier is open. */
    private static UiValue mastery(CharacterStats stats, PlayerClassDefinition cls, float x0, float x1, float s) {
        int[] gates = PlayerConstants.CLASS_TREE_TIER_GATES;
        int top = gates[gates.length - 1];
        int ranks = stats.getClassRanks(cls.id());
        float w = x1 - x0;
        List<UiValue> notches = new ArrayList<>();
        for (int t = 1; t < gates.length; t++) {
            int below = 0;
            for (String k : ClassRegistry.keysOf(cls)) {
                if (ClassRegistry.tierOf(k) < t) below += stats.getRank(k);
            }
            notches.add(obj("notch", "n" + t, "x", x0 + w * gates[t] / top, "label", String.valueOf(gates[t]),
                "lit", below >= gates[t]));
        }
        return obj("x", x0, "y", 4f * s, "w", w, "h", BAR_H * s, "fill", w * Math.min(ranks, top) / top,
            "label", ranks + (ranks == 1 ? " rank" : " ranks"), "notches", new UiValue.Arr(notches));
    }

    private static UiValue node(String key, String kind, String state, String name, String iconPath, String badge,
                                float cx, float rowY, float size, String focus, float s) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("key", UiValue.of(key));
        m.put("x", UiValue.of(cx - size / 2f));
        m.put("y", UiValue.of(rowY + NODE / 2f * s - size / 2f));
        m.put("size", UiValue.of(size));
        m.put("initials", UiValue.of(initials(name)));
        m.put("labelY", UiValue.of((size - 18f * s) / 2f));
        m.put("icon", obj("path", iconPath == null ? "" : iconPath));
        m.put("hasIcon", UiValue.of(iconPath != null));
        m.put("badge", UiValue.of(badge));
        m.put("badgeY", UiValue.of(size - 6f * s)); // the pill straddles the node's bottom edge
        for (String k : new String[]{"passive", "ability", "modifier", "capstone"}) {
            m.put(k, UiValue.of(k.equals(kind)));
        }
        for (String st : new String[]{"innate", "locked", "available", "invested", "maxed"}) {
            m.put(st, UiValue.of(st.equals(state)));
        }
        m.put("focused", UiValue.of(key.equals(focus)));
        return new UiValue.Obj(m);
    }

    /** locked (can't take a rank and has none), available, invested, or maxed. */
    static String state(CharacterStats stats, String key) {
        int rank = stats.getRank(key);
        if (rank >= ClassRegistry.maxRankOf(key)) return "maxed";
        if (rank > 0) return "invested";
        return stats.canAddRank(key) ? "available" : "locked";
    }

    private static String badge(CharacterStats stats, String key) {
        return stats.getRank(key) + "/" + ClassRegistry.maxRankOf(key);
    }

    private static UiValue detail(CharacterStats stats, PlayerClassDefinition cls, String focus, float x, float y,
                                  float w, float h) {
        String name;
        String kind;
        String rank;
        String description;
        String status;
        boolean ok;
        if (cls.id().equals(ClassRegistry.classIdOf(focus)) && ClassRegistry.isAbilityKey(focus)) {
            ClassAbility a = cls.abilities().get(ClassRegistry.abilityIndexOf(focus));
            name = a.name();
            kind = "Core ability";
            rank = "";
            description = a.description();
            ok = stats.hasAbility(focus);
            status = ok ? "Unlocked" : stats.lockReason(focus);
        } else if (!cls.id().equals(ClassRegistry.classIdOf(focus)) || !ClassRegistry.isValidKey(focus)) {
            name = cls.corePassive().name();
            kind = "Core passive";
            rank = "";
            description = cls.corePassive().description();
            status = "Always active";
            ok = true;
        } else {
            int r = stats.getRank(focus);
            int max = ClassRegistry.maxRankOf(focus);
            int tier = ClassRegistry.tierOf(focus);
            TalentNode node = ClassRegistry.findNode(focus).orElseThrow();
            name = node.name();
            kind = typeLabel(node.type()) + " - Tier " + TIER_NUMERALS[tier];
            description = node.description();
            rank = "Rank " + r + "/" + max;
            String lock = stats.lockReason(focus);
            ok = lock == null;
            status = r >= max ? "Max rank" : ok ? "Click to add a rank (" + stats.getCpCostPerRank(cls.id()) + " CP)"
                + unlocks(stats, cls, focus) : lock;
        }
        return obj("x", x, "y", y, "w", w, "h", h, "name", name, "kind", kind, "rank", rank,
            "description", description, "status", status, "ok", ok);
    }

    /** ", unlocks Rampage" when a rank in this node's path would unlock the path's ability, else "". */
    private static String unlocks(CharacterStats stats, PlayerClassDefinition cls, String nodeKey) {
        int path = Integer.parseInt(nodeKey.split(":")[1].substring(1));
        String ability = ClassRegistry.coreKey(cls.id(), cls.paths().get(path).ability());
        return stats.hasAbility(ability) ? "" : ", unlocks " + cls.abilities().get(cls.paths().get(path).ability()).name();
    }

    private static String typeLabel(TalentNodeType type) {
        String n = type.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String kind(TalentNodeType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    private static float size(TalentNodeType type) {
        return switch (type) {
            case CAPSTONE -> CAPSTONE;
            case MODIFIER -> MODIFIER;
            default -> NODE;
        };
    }

    /** Half the drawn width: a modifier's frame is turned 45°, so its corners reach out by half its diagonal. */
    private static float halfReach(TalentNodeType type) {
        return type == TalentNodeType.MODIFIER ? MODIFIER * 0.7071f : size(type) / 2f;
    }

    /** Up to two initials, skipping the "Ability:" prefix of a modifier ("Rampage: Bloodtrail" → "B"). */
    static String initials(String name) {
        String n = (name.contains(": ") ? name.substring(name.indexOf(": ") + 2) : name).replace("'", "");
        StringBuilder b = new StringBuilder();
        for (String word : n.split("[\\s'-]+")) {
            if (!word.isEmpty() && Character.isLetter(word.charAt(0)) && b.length() < 2) {
                b.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        return b.toString();
    }

    private static UiValue obj(Object... kv) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], val(kv[i + 1]));
        }
        return new UiValue.Obj(m);
    }

    private static UiValue val(Object v) {
        return switch (v) {
            case UiValue u -> u;
            case String str -> UiValue.of(str);
            case Boolean b -> UiValue.of(b);
            case Number n -> UiValue.of(n.doubleValue());
            default -> throw new IllegalArgumentException("not a UI value: " + v);
        };
    }
}
