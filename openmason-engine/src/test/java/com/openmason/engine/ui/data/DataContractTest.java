package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schemas, paths, cells, collections and the identity diff of the host data contract (#289). */
class DataContractTest {

    private static UiValue.Obj obj(Object... kv) {
        java.util.Map<String, UiValue> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], v instanceof UiValue u ? u : v instanceof String s ? UiValue.of(s)
                : v instanceof Boolean b ? UiValue.of(b) : UiValue.of(((Number) v).doubleValue()));
        }
        return new UiValue.Obj(m);
    }

    @Test
    void schemasNameTheMemberThatIsWrong() {
        DataType t = DataType.object("online", DataType.bool(), "players", DataType.list(
            DataType.object("id", DataType.string(), "ping", DataType.integer()), "id"));
        assertNull(t.problem(obj("online", true, "players", new UiValue.Arr(List.of(obj("id", "a", "ping", 3))))));
        assertEquals("players[0].ping: expects int, got number",
            t.problem(obj("online", true, "players", new UiValue.Arr(List.of(obj("id", "a", "ping", 3.5))))));
        assertEquals("online: expects bool, got null", t.problem(obj("players", new UiValue.Arr(List.of()))));
        assertTrue(t.problem(obj("online", true, "players", new UiValue.Arr(List.of()), "extra", 1)).contains("extra"));
        String dup = t.problem(obj("online", false, "players",
            new UiValue.Arr(List.of(obj("id", "a", "ping", 1), obj("id", "a", "ping", 2)))));
        assertTrue(dup.contains("repeats identity"), dup);
        assertNull(DataType.string().orNull().problem(UiValue.NULL));
    }

    @Test
    void pathsParseEvaluateAndRewrite() {
        DataPath p = DataPath.parse("session.players[1].name");
        assertEquals("session", p.rootName());
        assertEquals(".players[1].name", p.tail().toString());
        assertEquals("session.players[1].name", p.toString());
        UiValue root = obj("players", new UiValue.Arr(List.of(obj("name", "a"), obj("name", "b"))));
        assertEquals(UiValue.of("b"), p.evaluate(root));
        assertNull(DataPath.parse("session.players[5].name").evaluate(root));
        assertSame(DataPath.SELF, DataPath.parse("."));
        assertEquals(".a.b", DataPath.parse(".a").resolve(DataPath.parse(".b")).toString());
        assertEquals(UiValue.of("c"), p.evaluate(p.with(root, UiValue.of("c"))));
        assertEquals(DataType.string(), DataPath.parse("s.players[0].name")
            .typeFrom(DataType.object("players", DataType.list(DataType.object("name", DataType.string()), null))));
        assertThrows(IllegalArgumentException.class, () -> DataPath.parse("a..b"));
    }

    @Test
    void cellsNotifyOnlyRealChangesAndRejectMistypedValues() {
        DataCell cell = new DataCell(DataType.object("online", DataType.bool()), obj("online", false));
        List<DataState> seen = new ArrayList<>();
        Subscription sub = cell.subscribe((s, c) -> seen.add(s));
        cell.set(obj("online", false));
        cell.set(obj("online", true));
        cell.set(DataPath.parse(".online"), UiValue.FALSE);
        assertEquals(2, seen.size());
        assertThrows(IllegalArgumentException.class, () -> cell.set(obj("online", "yes")));
        sub.close();
        sub.close();
        assertEquals(0, cell.subscriberCount());
    }

    @Test
    void postsFromOtherThreadsCoalesceToTheLatestOnDrain() throws Exception {
        UiHost host = new UiHost();
        DataCell cell = host.data().register("furnace", new DataCell(DataType.object("progress", DataType.number()),
            obj("progress", 0)), HostContract.of("t:furnace", 1));
        List<DataState> seen = new ArrayList<>();
        cell.subscribe((s, c) -> seen.add(s));
        Thread t = new Thread(() -> {
            for (int i = 1; i <= 20; i++) {
                cell.post(obj("progress", i / 20.0));
            }
        });
        t.start();
        t.join();
        assertTrue(seen.isEmpty(), "nothing reaches the UI before the frame drains");
        host.drain();
        assertEquals(List.of(DataState.ready(obj("progress", 1.0))), seen);
        assertThrows(IllegalStateException.class,
            () -> new DataCell(DataType.ANY, UiValue.NULL).post(UiValue.NULL));
    }

    @Test
    void collectionsSendIncrementalChanges() {
        DataCollection c = new DataCollection(DataType.list(DataType.object("id", DataType.string(), "n", DataType.integer()), "id"));
        List<List<ListChange>> seen = new ArrayList<>();
        c.subscribe((s, ch) -> seen.add(ch));
        c.add(obj("id", "a", "n", 1));
        c.add(obj("id", "b", "n", 2));
        c.update(obj("id", "a", "n", 5));
        c.move(1, 0);
        c.remove("a");
        assertEquals(List.of(new ListChange.Inserted(0, obj("id", "a", "n", 1))), seen.get(0));
        assertEquals(List.of(new ListChange.Updated(0, obj("id", "a", "n", 5))), seen.get(2));
        assertEquals(List.of(new ListChange.Moved(1, 0)), seen.get(3));
        assertEquals(List.of(new ListChange.Removed(1, "s:a")), seen.get(4));
        assertEquals(List.of(obj("id", "b", "n", 2)), c.items());
        assertThrows(IllegalArgumentException.class, () -> c.add(obj("id", "b", "n", 0)), "identities are unique");
        assertThrows(IllegalArgumentException.class, () -> c.add(obj("n", 0)), "items need an identity");
    }

    @Test
    void identityDiffReplaysToTheTarget() {
        Random rnd = new Random(289);
        for (int round = 0; round < 300; round++) {
            List<UiValue> before = randomList(rnd);
            List<UiValue> after = randomList(rnd);
            List<ListChange> changes = ListDiff.diff(before, after, "id");
            assertEquals(after, ListDiff.apply(before, changes), () -> before + " -> " + after + " via " + changes);
            for (ListChange ch : changes) {
                if (ch instanceof ListChange.Inserted ins) {
                    String id = DataType.identityOf(ins.item(), "id");
                    assertTrue(before.stream().noneMatch(v -> id.equals(DataType.identityOf(v, "id"))),
                        "an item present before is moved or updated, never re-inserted");
                }
            }
        }
        assertEquals(List.of(ListChange.RESET), ListDiff.diff(List.of(), List.of(obj("x", 1)), "id"));
    }

    @Test
    void positionalDiffWithoutIdentity() {
        List<UiValue> a = List.of(UiValue.of(1), UiValue.of(2), UiValue.of(3));
        List<UiValue> b = List.of(UiValue.of(1), UiValue.of(9));
        assertEquals(b, ListDiff.apply(a, ListDiff.diff(a, b, null)));
    }

    private static List<UiValue> randomList(Random rnd) {
        List<String> ids = new ArrayList<>(List.of("a", "b", "c", "d", "e", "f", "g"));
        java.util.Collections.shuffle(ids, rnd);
        List<UiValue> out = new ArrayList<>();
        for (String id : ids.subList(0, rnd.nextInt(ids.size() + 1))) {
            out.add(obj("id", id, "v", rnd.nextInt(3)));
        }
        return out;
    }

    @Test
    void registryRejectsDuplicateAndMalformedRoots() {
        UiHost host = new UiHost();
        host.data().register("session", new DataCell(DataType.ANY, UiValue.NULL), HostContract.of("t:session", 2));
        assertThrows(IllegalArgumentException.class,
            () -> host.data().register("session", new DataCell(DataType.ANY, UiValue.NULL), HostContract.of("t:x", 1)));
        assertThrows(IllegalArgumentException.class,
            () -> host.data().register("bad-name", new DataCell(DataType.ANY, UiValue.NULL), HostContract.of("t:x", 1)));
        assertEquals(Map.of("t:session", 2), host.contracts());
    }
}
