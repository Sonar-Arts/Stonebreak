package com.stonebreak.ui.classTree;

import com.openmason.engine.format.omui.UiValue;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.classes.PlayerClassDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared class-tree layout: one node per core passive, core ability and path node; connectors that light
 * once their parent has a rank; node states and the mastery bar's notches following the tree rules; the detail
 * panel following focus; and the grid fitting the area it is given, its detail beside or under it.
 */
class ClassTreeModelTest {

    private static final PlayerClassDefinition BERSERKER = ClassRegistry.findById("berserker").orElseThrow();

    /** A level-1 character with {@code cp} CP to spend and nothing invested. */
    private static CharacterStats withCp(int cp) {
        CharacterStats cs = new CharacterStats(null);
        cs.restore(null, List.of(), Map.of(), Map.of(), Set.of(), cp, 0, 0,
                new int[]{10, 10, 10, 10, 10, 10}, 0, 1, 0);
        return cs;
    }

    private static void addRanks(CharacterStats cs, String key, int n) {
        for (int i = 0; i < n; i++) {
            assertTrue(cs.addRank(key), key + ": " + cs.lockReason(key));
        }
    }

    /** The sheet's tree area at scale 1: narrow, so the detail goes under the grid. */
    private static UiValue.Obj sheet(CharacterStats cs, String focus) {
        return (UiValue.Obj) ClassTreeModel.build(cs, BERSERKER, focus, 1f, 383f, 330f);
    }

    private static List<UiValue> list(UiValue.Obj o, String key) {
        return ((UiValue.Arr) o.get(key)).items();
    }

    private static UiValue.Obj obj(UiValue.Obj o, String key) {
        return (UiValue.Obj) o.get(key);
    }

    private static UiValue.Obj byKey(List<UiValue> items, String field, String key) {
        return items.stream().map(UiValue.Obj.class::cast).filter(i -> str(i, field).equals(key)).findFirst()
            .orElseThrow(() -> new AssertionError("no " + key));
    }

    private static String str(UiValue.Obj o, String key) {
        return ((UiValue.Str) o.get(key)).value();
    }

    private static float num(UiValue.Obj o, String key) {
        return (float) ((UiValue.Num) o.get(key)).value();
    }

    private static boolean bool(UiValue.Obj o, String key) {
        return ((UiValue.Bool) o.get(key)).value();
    }

    @Test
    void everyNodeOfTheTreeIsLaidOutOnce() {
        for (PlayerClassDefinition cls : ClassRegistry.ALL) {
            UiValue.Obj t = (UiValue.Obj) ClassTreeModel.build(withCp(1), cls, null, 1f, 383f, 330f);
            List<String> keys = list(t, "nodes").stream().map(n -> str((UiValue.Obj) n, "key")).toList();
            assertEquals(ClassTreeModel.passiveKey(cls), keys.get(0), "the core passive leads");
            int core = cls.abilities().size();
            for (int i = 0; i < core; i++) {
                assertEquals(ClassRegistry.coreKey(cls.id(), i), keys.get(1 + i), "then the core abilities");
            }
            assertEquals(ClassRegistry.keysOf(cls), keys.subList(1 + core, keys.size()), cls.id());
            List<String> subs = list(t, "rows").stream().map(r -> str((UiValue.Obj) r, "sub")).toList();
            for (int p = 0; p < cls.paths().size(); p++) {
                assertEquals(cls.abilities().get(cls.paths().get(p).ability()).name(), subs.get(1 + p),
                    "each path row names the ability it grants");
            }
            assertEquals(3 * 3, list(t, "links").size(), "three connectors per path");
            assertEquals(4, list(t, "rows").size(), "core plus three paths");
        }
    }

    @Test
    void nodeStatesFollowTheRules() {
        CharacterStats cs = withCp(20);
        String tierOne = ClassRegistry.pathKey("berserker", 0, 0);
        String tierTwo = ClassRegistry.pathKey("berserker", 0, 1);
        String capstone = ClassRegistry.pathKey("berserker", 0, 3);
        List<UiValue> nodes = list(sheet(cs, null), "nodes");
        assertTrue(bool(byKey(nodes, "key", tierOne), "available"));
        assertTrue(bool(byKey(nodes, "key", tierTwo), "locked"), "tier II is gated");
        assertTrue(bool(byKey(nodes, "key", capstone), "capstone"));
        assertTrue(bool(byKey(nodes, "key", "berserker:passive"), "innate"), "no rank in the class yet");

        addRanks(cs, tierOne, 5);
        nodes = list(sheet(cs, null), "nodes");
        assertTrue(bool(byKey(nodes, "key", tierOne), "invested"));
        assertEquals("5/8", str(byKey(nodes, "key", tierOne), "badge"));
        assertTrue(bool(byKey(nodes, "key", tierTwo), "available"), "five ranks open tier II, the parent has one");
        assertTrue(bool(byKey(nodes, "key", "berserker:passive"), "invested"), "the passive is live");

        addRanks(cs, tierOne, 3);
        assertTrue(bool(byKey(list(sheet(cs, null), "nodes"), "key", tierOne), "maxed"));
    }

    @Test
    void coreAbilitiesLightWithTheirPath() {
        CharacterStats cs = withCp(2);
        List<UiValue> nodes = list(sheet(cs, null), "nodes");
        assertTrue(bool(byKey(nodes, "key", "berserker:0"), "locked"));
        assertEquals("", str(byKey(nodes, "key", "berserker:0"), "badge"), "core abilities take no ranks");

        addRanks(cs, ClassRegistry.pathKey("berserker", 2, 0), 1); // Frenzy grants Rampage
        nodes = list(sheet(cs, null), "nodes");
        assertTrue(bool(byKey(nodes, "key", "berserker:0"), "invested"));
        assertTrue(bool(byKey(nodes, "key", "berserker:1"), "locked"), "Skull Crusher needs Wrecker");

        UiValue.Obj d = obj(sheet(cs, "berserker:1"), "detail");
        assertEquals("Core ability", str(d, "kind"));
        assertEquals("Comes with Wrecker", str(d, "status"));
        assertFalse(bool(d, "ok"));
        assertEquals("Click to add a rank (1 CP), unlocks Skull Crusher",
            str(obj(sheet(cs, ClassRegistry.pathKey("berserker", 1, 0)), "detail"), "status"));
        assertEquals("Click to add a rank (1 CP)",
            str(obj(sheet(cs, ClassRegistry.pathKey("berserker", 0, 0)), "detail"), "status"),
            "Bloodletter's Rampage is already unlocked");
    }

    @Test
    void connectorsLightOnceTheParentHasARank() {
        CharacterStats cs = withCp(20);
        String tierTwo = ClassRegistry.pathKey("berserker", 1, 1);
        assertFalse(bool(byKey(list(sheet(cs, null), "links"), "link", tierTwo), "lit"));
        addRanks(cs, ClassRegistry.pathKey("berserker", 1, 0), 1);
        assertTrue(bool(byKey(list(sheet(cs, null), "links"), "link", tierTwo), "lit"));
        assertFalse(bool(byKey(list(sheet(cs, null), "links"), "link", ClassRegistry.pathKey("berserker", 1, 2)),
            "lit"), "the next connector waits for tier II");
    }

    @Test
    void theMasteryBarFillsWithClassRanksAndLightsOpenTiers() {
        CharacterStats cs = withCp(30);
        UiValue.Obj m = obj(sheet(cs, null), "mastery");
        assertEquals(0f, num(m, "fill"));
        assertFalse(bool((UiValue.Obj) list(m, "notches").get(0), "lit"));

        addRanks(cs, ClassRegistry.pathKey("berserker", 1, 0), 5);
        m = obj(sheet(cs, null), "mastery");
        assertEquals(num(m, "w") * 5f / 30f, num(m, "fill"), 1e-3);
        assertEquals("5 ranks", str(m, "label"));
        assertTrue(bool((UiValue.Obj) list(m, "notches").get(0), "lit"), "tier II open");
        assertFalse(bool((UiValue.Obj) list(m, "notches").get(1), "lit"), "tier III needs 15");
    }

    @Test
    void theDetailPanelDescribesTheFocusedNode() {
        CharacterStats cs = withCp(0);
        UiValue.Obj d = obj(sheet(cs, null), "detail");
        assertEquals(BERSERKER.corePassive().name(), str(d, "name"), "no focus: the core passive");
        assertTrue(bool(d, "ok"));

        String node = ClassRegistry.pathKey("berserker", 0, 2);
        d = obj(sheet(cs, node), "detail");
        assertEquals(ClassRegistry.findNode(node).orElseThrow().name(), str(d, "name"));
        assertEquals("Modifier - Tier III", str(d, "kind"));
        assertEquals("Rank 0/5", str(d, "rank"));
        assertEquals(cs.lockReason(node), str(d, "status"));
        assertFalse(bool(d, "ok"));

        assertTrue(bool(byKey(list(sheet(cs, node), "nodes"), "key", node), "focused"));
        d = obj(sheet(cs, "rogue:0"), "detail");
        assertEquals(BERSERKER.corePassive().name(), str(d, "name"), "another class's key: the passive");
    }

    @Test
    void theGridFitsItsAreaWithTheDetailBesideOrUnder() {
        CharacterStats cs = withCp(1);
        for (float w : new float[]{383f, 560f, 572f, 1060f}) {
            UiValue.Obj t = (UiValue.Obj) ClassTreeModel.build(cs, BERSERKER, null, 1f, w, 400f);
            UiValue.Obj d = obj(t, "detail");
            for (UiValue v : list(t, "nodes")) {
                UiValue.Obj n = (UiValue.Obj) v;
                assertTrue(num(n, "x") >= 0f && num(n, "x") + num(n, "size") <= num(t, "clipW"),
                    w + ": " + str(n, "key") + " inside the grid's clip");
            }
            assertTrue(num(d, "x") + num(d, "w") <= w + 1e-3, w + ": the detail inside the area");
            boolean beside = num(d, "y") == 0f;
            assertEquals(w >= 572f, beside, w + ": the detail goes beside the grid only when both fit");
            if (beside) {
                assertTrue(num(d, "x") >= num(t, "clipW"), w + ": right of the grid's clip");
            } else {
                assertTrue(num(d, "y") >= num(t, "clipH"), w + ": under the grid's clip");
            }
        }
    }

    @Test
    void initialsSkipTheModifiedAbilitysName() {
        assertEquals("OV", ClassTreeModel.initials("Open Veins"));
        assertEquals("B", ClassTreeModel.initials("Rampage: Bloodtrail"));
        assertEquals("HM", ClassTreeModel.initials("Hunter's Mark"));
        assertEquals("AP", ClassTreeModel.initials("Apex Predator"));
    }
}
