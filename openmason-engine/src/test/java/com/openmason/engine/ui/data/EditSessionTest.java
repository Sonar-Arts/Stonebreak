package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Draft, validation, commit and rollback of two-way edits (#289). */
class EditSessionTest {

    private static final HostContract SETTINGS = HostContract.of("t:settings", 1);
    private static final DataPath FOV = DataPath.parse("settings.fov");
    private static final DataPath NAME = DataPath.parse("settings.name");

    private final UiHost host = new UiHost();
    private final List<UiValue> saved = new ArrayList<>();
    private CompletableFuture<UiValue> commit;
    private final DataCell cell;

    EditSessionTest() {
        DataType.Obj type = DataType.object("fov", DataType.integer(), "name", DataType.string());
        cell = host.data().registerEditable("settings",
            new DataCell(type, new UiValue.Obj(Map.of("fov", UiValue.of(70), "name", UiValue.of("Steve")))),
            SETTINGS, new EditPolicy("t:settings.apply", (path, value, draft) ->
                path.equals(FOV) && (((UiValue.Num) value).value() < 30 || ((UiValue.Num) value).value() > 110)
                    ? "field of view must be 30-110" : null));
        host.actions().register(ActionSpec.of("t:settings.apply", SETTINGS, DataType.object("value", type), DataType.ANY),
            (args, ctx) -> {
                commit = new CompletableFuture<>();
                UiValue v = args.get("value");
                return commit.thenApply(r -> {
                    saved.add(v);
                    cell.set(v); // the host saves, then publishes the new committed value
                    return UiValue.NULL;
                });
            });
    }

    private UiScope open() {
        return host.openScope("t:ui/settings", Set.of("t:settings", "t:session"), p -> { });
    }

    @Test
    void stagingValidatesAndShowsTheDraftWithoutSaving() {
        UiScope s = open();
        List<DataState> seen = new ArrayList<>();
        s.watch(FOV, seen::add);
        CallSite site = s.site("fov_field", CallSite.Origin.BINDING);
        assertFalse(s.edits().stage(FOV, UiValue.of(200), site).accepted(), "validator rejects");
        assertFalse(s.edits().stage(FOV, UiValue.of("wide"), site).accepted(), "schema rejects");
        assertFalse(s.edits().stage(DataPath.parse("settings.volume"), UiValue.of(1), site).accepted());
        assertTrue(seen.isEmpty());
        assertTrue(s.edits().stage(FOV, UiValue.of(90), site).accepted());
        assertEquals(List.of(DataState.ready(UiValue.of(90))), seen);
        assertEquals(UiValue.of(70), DataPath.parse("settings.fov").evaluate(cell.value()), "the source is untouched");
        assertTrue(s.edits().isDirty());
        UiScope other = open();
        assertEquals(DataState.ready(UiValue.of(70)), other.read(FOV), "drafts are per scope");
    }

    @Test
    void cancelRollsBack() {
        UiScope s = open();
        s.edits().stage(FOV, UiValue.of(90), s.site("f", CallSite.Origin.BINDING));
        s.edits().cancel();
        assertEquals(DataState.ready(UiValue.of(70)), s.read(FOV));
        assertFalse(s.edits().isDirty());
        assertTrue(saved.isEmpty());
    }

    @Test
    void applyCommitsThroughTheHostAction() {
        UiScope s = open();
        CallSite site = s.site("apply", CallSite.Origin.SCRIPT);
        s.edits().stage(FOV, UiValue.of(90), site);
        s.edits().stage(NAME, UiValue.of("Alex"), site);
        ActionCall call = s.edits().apply("settings", site);
        assertTrue(call.isPending());
        assertEquals(ActionCall.State.REJECTED, s.edits().apply("settings", site).state(), "double Apply is refused");
        commit.complete(UiValue.NULL);
        host.drain();
        assertEquals(ActionCall.State.SUCCEEDED, call.state());
        assertEquals(List.of(new UiValue.Obj(Map.of("fov", UiValue.of(90), "name", UiValue.of("Alex")))), saved);
        assertFalse(s.edits().isDirty());
        assertEquals(DataState.ready(UiValue.of(90)), s.read(FOV));
        assertNull(s.edits().apply("settings", site), "nothing left to apply");
    }

    @Test
    void failedApplyKeepsTheDraft() {
        UiScope s = open();
        CallSite site = s.site("apply", CallSite.Origin.SCRIPT);
        s.edits().stage(FOV, UiValue.of(90), site);
        ActionCall call = s.edits().apply("settings", site);
        commit.completeExceptionally(new IllegalStateException("disk full"));
        host.drain();
        assertEquals(ActionCall.State.FAILED, call.state());
        assertTrue(s.edits().isDirty());
        assertEquals("disk full", s.edits().lastError("settings"));
        assertEquals(DataState.ready(UiValue.of(90)), s.read(FOV));
    }

    @Test
    void readOnlyRootsCannotBeEdited() {
        host.data().register("session", new DataCell(DataType.object("online", DataType.bool()),
            new UiValue.Obj(Map.of("online", UiValue.FALSE))), HostContract.of("t:session", 1));
        UiScope s = open();
        EditSession.Result r = s.edits().stage(DataPath.parse("session.online"), UiValue.TRUE,
            s.site("x", CallSite.Origin.BINDING));
        assertFalse(r.accepted());
        assertTrue(r.problem().contains("read-only"));
    }
}
