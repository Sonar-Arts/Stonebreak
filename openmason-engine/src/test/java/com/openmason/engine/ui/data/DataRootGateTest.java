package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A scope only lets its document observe the data roots whose contracts it declares (#327). */
class DataRootGateTest {

    private static final DataPath ONLINE = DataPath.parse("session.online");
    private static final DataPath HEALTH = DataPath.parse("vitals.health");

    private final UiHost host = new UiHost();
    private final DataCell session = host.data().register("session",
        new DataCell(DataType.object("online", DataType.bool()), new UiValue.Obj(Map.of("online", UiValue.TRUE))),
        HostContract.of("t:session", 1));
    private final DataCell vitals = host.data().registerEditable("vitals",
        new DataCell(DataType.object("health", DataType.integer()), new UiValue.Obj(Map.of("health", UiValue.of(20)))),
        HostContract.of("t:vitals", 1), new EditPolicy("t:vitals.apply", null));

    private UiScope declaring(String... contracts) {
        return host.openScope("t:ui/x", Set.of(contracts), p -> { });
    }

    @Test
    void declaredRootsReadAndWatchAsBefore() {
        UiScope s = declaring("t:session");
        assertTrue(s.declares("session"));
        assertEquals(DataState.ready(UiValue.TRUE), s.read(ONLINE));
        List<DataState> seen = new ArrayList<>();
        s.watch(ONLINE, seen::add);
        session.set(new UiValue.Obj(Map.of("online", UiValue.FALSE)));
        assertEquals(List.of(DataState.ready(UiValue.FALSE)), seen);
        s.requireDeclared(ONLINE, s.site("x", CallSite.Origin.SCRIPT)); // no throw
        s.close();
    }

    @Test
    void undeclaredRootsReadAsFailedAndLeakNothing() {
        UiScope s = declaring("t:session");
        assertFalse(s.declares("vitals"));
        DataState st = s.read(HEALTH);
        DataState.Failed f = assertInstanceOf(DataState.Failed.class, st);
        assertTrue(f.message().contains("CAPABILITY_MISSING") && f.message().contains("t:vitals"), f.message());
        assertNull(st.valueOrNull());
        assertNull(s.typeOf(HEALTH), "not even the schema");
        assertNotNull(s.typeOf(ONLINE));
        s.close();
    }

    @Test
    void undeclaredRootsCannotBeWatched() {
        UiScope s = declaring("t:session");
        List<DataState> seen = new ArrayList<>();
        assertSame(Subscription.NONE, s.watch(HEALTH, seen::add));
        assertSame(Subscription.NONE, s.watchList(HEALTH, (state, changes) -> seen.add(state)));
        assertEquals(0, vitals.subscriberCount(), "the source was never subscribed");
        vitals.set(new UiValue.Obj(Map.of("health", UiValue.of(3))));
        assertTrue(seen.isEmpty());
        assertEquals(0, s.subscriptionCount());
        s.close();
    }

    @Test
    void requireDeclaredFailsLoudlyWithTheContractAndCallSite() {
        UiScope s = declaring("t:session");
        CallSite site = s.site("hud/label", CallSite.Origin.SCRIPT);
        UiActionException e = assertThrows(UiActionException.class, () -> s.requireDeclared(HEALTH, site));
        assertEquals(UiActionException.Code.CAPABILITY_MISSING, e.code());
        assertEquals("vitals.health", e.actionId());
        assertTrue(e.getMessage().contains("t:vitals") && e.getMessage().contains("hostApis"), e.getMessage());
        assertTrue(e.getMessage().contains("node hud/label"), e.getMessage());
        // An unknown root is not a capability problem: it simply reads as missing.
        s.requireDeclared(DataPath.parse("nothing.here"), site);
        assertEquals(DataState.MISSING, s.read(DataPath.parse("nothing.here")));
        s.close();
    }

    @Test
    void undeclaredRootsCannotBeEdited() {
        UiScope s = declaring("t:session");
        EditSession.Result r = s.edits().stage(HEALTH, UiValue.of(1), s.site("x", CallSite.Origin.BINDING));
        assertFalse(r.accepted());
        assertTrue(r.problem().contains("CAPABILITY_MISSING"), r.problem());
        assertFalse(s.edits().isDirty());
        s.close();
        UiScope ok = declaring("t:session", "t:vitals");
        assertTrue(ok.edits().stage(HEALTH, UiValue.of(1), ok.site("x", CallSite.Origin.BINDING)).accepted());
        ok.close();
    }

    @Test
    void anUncheckedScopeSeesEveryRoot() {
        UiScope s = host.openScope("t:ui/x", null, p -> { });
        assertTrue(s.declares("vitals"));
        assertEquals(DataState.ready(UiValue.of(20)), s.read(HEALTH));
        s.close();
    }
}
