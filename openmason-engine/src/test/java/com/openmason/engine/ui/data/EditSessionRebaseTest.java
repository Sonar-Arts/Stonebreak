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

/**
 * The draft follows the source (#289 review): a change made elsewhere while a settings screen
 * edits shows through untouched members, and apply never writes an old value back.
 */
class EditSessionRebaseTest {

    private static final HostContract SETTINGS = HostContract.of("t:settings", 1);
    private static final DataPath FOV = DataPath.parse("settings.fov");
    private static final DataPath VOLUME = DataPath.parse("settings.volume");

    private final UiHost host = new UiHost();
    private final List<UiValue.Obj> commits = new ArrayList<>();
    private final DataCell cell;

    EditSessionRebaseTest() {
        DataType.Obj type = DataType.object("fov", DataType.integer(), "volume", DataType.number());
        cell = host.data().registerEditable("settings", new DataCell(type, settings(70, 0.5)), SETTINGS,
            new EditPolicy("t:settings.apply", null));
        host.actions().register(ActionSpec.of("t:settings.apply", SETTINGS,
                DataType.object("value", type, "changed", DataType.list(DataType.string(), null)), DataType.ANY),
            (args, ctx) -> {
                commits.add(args);
                cell.set(args.get("value"));
                return CompletableFuture.completedFuture(UiValue.NULL);
            });
    }

    private static UiValue.Obj settings(int fov, double volume) {
        return new UiValue.Obj(Map.of("fov", UiValue.of(fov), "volume", UiValue.of(volume)));
    }

    private UiScope open() {
        return host.openScope("t:ui/settings", Set.of("t:settings"), p -> { });
    }

    @Test
    void aSourceChangeShowsThroughMembersThisScreenDidNotTouch() {
        UiScope s = open();
        CallSite site = s.site("fov", CallSite.Origin.BINDING);
        s.edits().stage(FOV, UiValue.of(90), site);
        cell.set(settings(70, 0.8)); // a volume hotkey while the settings screen is open
        assertEquals(DataState.ready(UiValue.of(0.8)), s.read(VOLUME));
        assertEquals(DataState.ready(UiValue.of(90)), s.read(FOV));

        s.edits().apply("settings", site);
        assertEquals(settings(90, 0.8), cell.value(), "apply keeps the newer volume");
        assertEquals(new UiValue.Arr(List.of(UiValue.of("settings.fov"))), commits.getFirst().get("changed"),
            "a commit action that declares 'changed' is told what was edited");
        assertFalse(s.edits().isDirty());
    }

    @Test
    void aMemberChangedElsewhereAfterStagingIsAConflictTheStagedValueStillWins() {
        UiScope s = open();
        CallSite site = s.site("fov", CallSite.Origin.BINDING);
        s.edits().stage(FOV, UiValue.of(90), site);
        assertTrue(s.edits().conflicts("settings").isEmpty());
        cell.set(settings(100, 0.5));
        assertEquals(List.of(FOV), s.edits().conflicts("settings"));
        assertEquals(DataState.ready(UiValue.of(90)), s.read(FOV));

        s.edits().cancel("settings", FOV);
        assertEquals(DataState.ready(UiValue.of(100)), s.read(FOV), "dropping the member takes the source's value");
        assertFalse(s.edits().isDirty());
    }

    @Test
    void stagingTheCommittedValueAgainLeavesNothingToApply() {
        UiScope s = open();
        CallSite site = s.site("fov", CallSite.Origin.BINDING);
        s.edits().stage(FOV, UiValue.of(90), site);
        s.edits().stage(FOV, UiValue.of(70), site);
        assertFalse(s.edits().isDirty());
        assertNull(s.edits().apply("settings", site));
        assertTrue(commits.isEmpty());
    }

    @Test
    void aSourceThatCatchesUpWithTheDraftIsNoLongerDirty() {
        UiScope s = open();
        s.edits().stage(FOV, UiValue.of(90), s.site("fov", CallSite.Origin.BINDING));
        cell.set(settings(90, 0.5)); // someone else applied the same value
        assertFalse(s.edits().isDirty());
        assertTrue(s.edits().dirtyRoots().isEmpty());
    }

    @Test
    void editsStagedWhileACommitRunsStayDrafted() {
        CompletableFuture<UiValue> pending = new CompletableFuture<>();
        UiHost h = new UiHost();
        DataType.Obj type = DataType.object("fov", DataType.integer(), "volume", DataType.number());
        DataCell c = h.data().registerEditable("settings", new DataCell(type, settings(70, 0.5)), SETTINGS,
            new EditPolicy("t:settings.apply", null));
        h.actions().register(ActionSpec.of("t:settings.apply", SETTINGS, DataType.object("value", type), DataType.ANY)
                .withReentrancy(ActionSpec.Reentrancy.PARALLEL),
            (args, ctx) -> pending.thenApply(r -> {
                c.set(args.get("value"));
                return r;
            }));
        UiScope s = h.openScope("t:ui/settings", Set.of("t:settings"), p -> { });
        CallSite site = s.site("x", CallSite.Origin.BINDING);
        s.edits().stage(FOV, UiValue.of(90), site);
        ActionCall call = s.edits().apply("settings", site);
        s.edits().stage(VOLUME, UiValue.of(0.2), site);
        pending.complete(UiValue.NULL);
        h.drain();
        assertEquals(ActionCall.State.SUCCEEDED, call.state());
        assertEquals(List.of(VOLUME), s.edits().stagedPaths("settings"));
        assertEquals(DataState.ready(UiValue.of(90)), s.read(FOV));
        assertEquals(DataState.ready(UiValue.of(0.2)), s.read(VOLUME));
    }
}
