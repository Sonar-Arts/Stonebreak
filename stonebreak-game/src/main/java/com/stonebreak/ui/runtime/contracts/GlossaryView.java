package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.data.DataType;
import com.stonebreak.mobs.entities.EntityAttributes;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.EntityDiscoveries;
import com.stonebreak.player.PlayerStats;
import com.stonebreak.ui.glossaryScreen.GlossaryScreen;
import com.stonebreak.ui.glossaryScreen.GlossaryText;
import com.stonebreak.ui.glossaryScreen.SkijaGlossaryRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The entity glossary for its document ({@code stonebreak:screen.glossary}, #299): what the legacy
 * renderer computes from the player's discoveries and kills and the screen's selection, as two
 * roots: {@code glossary} (header, the sidebar rows {@code entries[i]} in {@code GLOSSARY_TYPES}
 * order, selected entity, attributes, weakness) and the selected entity's {@code glossaryAbilities}. Every string comes
 * from {@link GlossaryText}, the formatter the legacy renderer uses. Republished only when a value
 * changed; nothing is computed while the glossary is closed.
 */
public final class GlossaryView {

    private static final String[] ATTR_KEYS = {"str", "dex", "con", "int", "wis", "cha"};
    private static final DataType.Obj ATTR = DataType.object("value", DataType.string(), "fraction", DataType.number());

    public static final DataType.Obj ENTRY = DataType.object("id", DataType.string(),
        "index", DataType.integer(), "name", DataType.string(), "sub", DataType.string(), "observed", DataType.bool(),
        "complete", DataType.bool(), "selected", DataType.bool());
    public static final DataType.Obj TYPE = type();
    public static final DataType.ListOf ABILITIES = DataType.list(DataType.object("id", DataType.string(),
        "text", DataType.string()), "id");

    private final DataCell root = new DataCell(TYPE, empty());
    private final DataCollection abilities = new DataCollection(ABILITIES);
    private UiValue lastRoot;
    private List<UiValue> lastAbilities = List.of();

    public DataCell root() {
        return root;
    }

    public DataCollection abilities() {
        return abilities;
    }

    /** UI thread: republishes what changed on the open glossary; nothing when it is null. */
    public void refresh(GlossaryScreen screen) {
        if (screen == null) {
            return;
        }
        EntityDiscoveries d = screen.discoveries();
        PlayerStats stats = screen.stats();
        EntityType type = screen.getSelectedEntityType();
        UiValue.Obj v = value(screen, type, d, stats);
        if (!v.equals(lastRoot)) {
            lastRoot = v;
            root.set(v);
        }
        List<UiValue> ab = abilities(type);
        if (!ab.equals(lastAbilities)) {
            lastAbilities = ab;
            abilities.setAll(ab);
        }
    }

    /** Back to empty (world left). */
    public void clear() {
        lastRoot = null;
        lastAbilities = List.of();
        root.set(empty());
        abilities.setAll(List.of());
    }

    static UiValue.Obj value(GlossaryScreen screen, EntityType type, EntityDiscoveries d, PlayerStats stats) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        int total = EntityType.GLOSSARY_TYPES.length;
        int seen = 0;
        for (EntityType t : EntityType.GLOSSARY_TYPES) {
            if (!SkijaGlossaryRenderer.discoveredVariants(t, d).isEmpty()) {
                seen++;
            }
        }
        m.put("observedText", UiValue.of(GlossaryText.observed(seen, total)));
        m.put("observedFraction", UiValue.of(total > 0 ? (double) ((float) seen / total) : 0));

        long kills = stats != null ? stats.getKillsByType().getOrDefault(type, 0L) : 0L;
        m.put("name", UiValue.of(type.getDisplayName()));
        m.put("badge", UiValue.of(GlossaryText.badge(kills)));
        m.put("defeated", UiValue.of(kills > 0));

        List<String> variants = SkijaGlossaryRenderer.discoveredVariants(type, d);
        int count = variants.size();
        int idx = screen.getSelectedVariantIndex(type, count);
        String variant = count > 0 ? variants.get(idx) : "";
        m.put("discovered", UiValue.of(count > 0));
        m.put("cycler", UiValue.of(count > 1));
        m.put("chip", UiValue.of(count > 0 ? GlossaryText.chip(variant, idx, count) : ""));
        m.put("preview", new UiValue.Obj(Map.of("entity", UiValue.of(type.name()), "variant", UiValue.of(variant))));

        EntityAttributes attrs = type.getAttributes();
        boolean unlocked = kills > 0 && attrs != null;
        m.put("unlocked", UiValue.of(unlocked));
        Map<String, UiValue> a = new LinkedHashMap<>();
        int[] scores = unlocked ? GlossaryText.scores(attrs) : null;
        for (int i = 0; i < ATTR_KEYS.length; i++) {
            a.put(ATTR_KEYS[i], new UiValue.Obj(Map.of(
                "value", UiValue.of(unlocked ? GlossaryText.score(scores[i]) : GlossaryText.LOCKED_VALUE),
                "fraction", UiValue.of(unlocked ? (double) (scores[i] / 20f) : 0))));
        }
        m.put("attributes", new UiValue.Obj(a));
        String[] derived = unlocked ? GlossaryText.derived(attrs) : new String[]{"", "", ""};
        m.put("derived", new UiValue.Obj(Map.of("hp", UiValue.of(derived[0]), "spd", UiValue.of(derived[1]),
            "atk", UiValue.of(derived[2]))));

        boolean known = d != null && d.isWeaknessDiscovered(type);
        LivingEntity.DamageSource weakness = type.getWeakness();
        m.put("weaknessKnown", UiValue.of(known));
        m.put("weaknessName", UiValue.of(known ? (weakness != null ? weakness.name() : "None") : GlossaryText.WEAKNESS_UNKNOWN));
        String desc = known ? type.getWeaknessDescription() : GlossaryText.WEAKNESS_HINT;
        m.put("weaknessText", UiValue.of(desc == null ? "" : desc));
        String[] abilities = type.getSpecialAbilities();
        m.put("hasAbilities", UiValue.of(abilities != null && abilities.length > 0));
        m.put("entries", new UiValue.Arr(rows(screen, d)));
        return new UiValue.Obj(m);
    }

    static List<UiValue> rows(GlossaryScreen screen, EntityDiscoveries d) {
        List<UiValue> out = new ArrayList<>();
        for (int i = 0; i < EntityType.GLOSSARY_TYPES.length; i++) {
            EntityType t = EntityType.GLOSSARY_TYPES[i];
            int seen = SkijaGlossaryRenderer.discoveredVariants(t, d).size();
            String[] all = t.getTextureVariants();
            int totalVariants = all != null ? all.length : 0;
            Map<String, UiValue> r = new LinkedHashMap<>();
            r.put("id", UiValue.of(t.name()));
            r.put("index", UiValue.of(i));
            r.put("name", UiValue.of(t.getDisplayName()));
            r.put("sub", UiValue.of(GlossaryText.rowSubtitle(seen, totalVariants)));
            r.put("observed", UiValue.of(seen > 0));
            r.put("complete", UiValue.of(seen > 0 && seen >= totalVariants && totalVariants > 0));
            r.put("selected", UiValue.of(i == screen.getSelectedEntityIndex()));
            out.add(new UiValue.Obj(r));
        }
        return out;
    }

    static List<UiValue> abilities(EntityType type) {
        String[] all = type.getSpecialAbilities();
        List<UiValue> out = new ArrayList<>();
        for (int i = 0; all != null && i < all.length; i++) {
            out.add(new UiValue.Obj(Map.of("id", UiValue.of(type.name() + ":" + i), "text", UiValue.of(all[i]))));
        }
        return out;
    }

    private static UiValue.Obj empty() {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("observedText", UiValue.of(""));
        m.put("observedFraction", UiValue.of(0));
        m.put("name", UiValue.of(""));
        m.put("badge", UiValue.of(""));
        m.put("defeated", UiValue.FALSE);
        m.put("discovered", UiValue.FALSE);
        m.put("cycler", UiValue.FALSE);
        m.put("chip", UiValue.of(""));
        m.put("preview", new UiValue.Obj(Map.of("entity", UiValue.of(""), "variant", UiValue.of(""))));
        m.put("unlocked", UiValue.FALSE);
        Map<String, UiValue> a = new LinkedHashMap<>();
        for (String k : ATTR_KEYS) {
            a.put(k, new UiValue.Obj(Map.of("value", UiValue.of(""), "fraction", UiValue.of(0))));
        }
        m.put("attributes", new UiValue.Obj(a));
        m.put("derived", new UiValue.Obj(Map.of("hp", UiValue.of(""), "spd", UiValue.of(""), "atk", UiValue.of(""))));
        m.put("weaknessKnown", UiValue.FALSE);
        m.put("weaknessName", UiValue.of(""));
        m.put("weaknessText", UiValue.of(""));
        m.put("hasAbilities", UiValue.FALSE);
        m.put("entries", new UiValue.Arr(List.of()));
        return new UiValue.Obj(m);
    }

    private static DataType.Obj type() {
        Map<String, DataType> a = new LinkedHashMap<>();
        for (String k : ATTR_KEYS) {
            a.put(k, ATTR);
        }
        Map<String, DataType> f = new LinkedHashMap<>();
        f.put("observedText", DataType.string());
        f.put("observedFraction", DataType.number());
        f.put("name", DataType.string());
        f.put("badge", DataType.string());
        f.put("defeated", DataType.bool());
        f.put("discovered", DataType.bool());
        f.put("cycler", DataType.bool());
        f.put("chip", DataType.string());
        f.put("preview", DataType.object("entity", DataType.string(), "variant", DataType.string()));
        f.put("unlocked", DataType.bool());
        f.put("attributes", DataType.object(a));
        f.put("derived", DataType.object("hp", DataType.string(), "spd", DataType.string(), "atk", DataType.string()));
        f.put("weaknessKnown", DataType.bool());
        f.put("weaknessName", DataType.string());
        f.put("weaknessText", DataType.string());
        f.put("hasAbilities", DataType.bool());
        f.put("entries", DataType.list(ENTRY, "id"));
        return DataType.object(f);
    }
}
