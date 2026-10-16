package com.openmason.main.systems.layout;

import com.openmason.api.workspace.WorkspaceDescriptor;
import com.openmason.api.workspace.WorkspaceDock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-workspace default-layout versions (#379): each workspace is rebuilt once when its own
 * version goes up, never because another workspace's did, and the legacy keys migrate without
 * an extra rebuild.
 */
class WorkspaceLayoutsTest {

    /** In-memory version store; absent = what the legacy fallback would answer. */
    private static final class Store implements WorkspaceLayouts.VersionStore {
        final Map<String, Integer> versions = new HashMap<>();

        @Override
        public int stored(String workspaceId) {
            return versions.getOrDefault(workspaceId, 0);
        }

        @Override
        public void store(String workspaceId, int version) {
            versions.put(workspaceId, version);
        }
    }

    private final List<String> builds = new ArrayList<>();
    private final Store store = new Store();
    private boolean saved;

    private WorkspaceDescriptor workspace(String id, int version) {
        WorkspaceDock dock = (dockspaceId, w, h) -> builds.add(id + "@" + version);
        return WorkspaceDescriptor.builder(id, id).layoutVersion(version).dock(dock).build();
    }

    private WorkspaceLayouts layouts() {
        return new WorkspaceLayouts(store, dockspaceId -> saved);
    }

    @Test
    void aWorkspaceWithoutASavedArrangementIsBuiltOnceAndRecorded() {
        WorkspaceLayouts layouts = layouts();
        WorkspaceDescriptor ws = workspace("plugin.ws", 1);

        assertTrue(layouts.applyIfNeeded(ws, 1, 800, 600));
        assertFalse(layouts.applyIfNeeded(ws, 1, 800, 600), "checked once per session");
        assertEquals(List.of("plugin.ws@1"), builds);
        assertEquals(1, store.versions.get("plugin.ws"));
    }

    @Test
    void aVersionBumpRebuildsOnlyThatWorkspace() {
        saved = true;
        store.store("A", 3);
        store.store("B", 2);

        WorkspaceLayouts layouts = layouts();
        assertTrue(layouts.applyIfNeeded(workspace("A", 4), 1, 800, 600), "A went 3 -> 4");
        assertFalse(layouts.applyIfNeeded(workspace("B", 2), 2, 800, 600), "B is current: the user's layout stays");
        assertEquals(List.of("A@4"), builds);
        assertEquals(4, store.versions.get("A"));
    }

    @Test
    void aResetRebuildsThatWorkspaceAgainEvenWhenCurrent() {
        saved = true;
        store.store("A", 1);
        WorkspaceLayouts layouts = layouts();
        WorkspaceDescriptor a = workspace("A", 1);
        assertFalse(layouts.applyIfNeeded(a, 1, 800, 600));

        layouts.requestReset("A");
        assertTrue(layouts.isResetPending("A"));
        assertTrue(layouts.applyIfNeeded(a, 1, 800, 600));
        assertFalse(layouts.isResetPending("A"));
        assertFalse(layouts.applyIfNeeded(a, 1, 800, 600));
        assertEquals(List.of("A@1"), builds);
    }

    @Test
    void registeringAWorkspaceLateAddsItsTab() {
        var registry = WorkspaceFixtures.builtins();
        WorkspaceState state = new WorkspaceState(registry);
        registry.register("plugin", workspace("plugin.ws", 1));

        assertTrue(state.isOpen("plugin.ws"), "a fixed workspace is open as soon as it registers");
        state.set("plugin.ws");
        assertEquals("plugin.ws", state.current());

        registry.removeOwner("plugin");
        assertFalse(state.isOpen("plugin.ws"));
        assertEquals(BuiltinWorkspaces.MODELING, state.current(), "removing the front tab falls back to Scene");
    }

    @Test
    void unknownStoredWorkspacesResolveToScene() {
        WorkspaceState state = WorkspaceFixtures.state();
        assertEquals(BuiltinWorkspaces.MODELING, state.resolve("some.uninstalled.plugin"));
        state.restore("some.uninstalled.plugin", List.of("also.gone"));
        assertEquals(BuiltinWorkspaces.MODELING, state.current());
        assertTrue(state.detached().isEmpty());
    }
}
