package com.openmason.main.systems.layout;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The main window's Scene | UI | Texture tabs: switching, popping tabs out, the one-tab-stays rule,
 * and the closable Texture tab's open/close lifecycle.
 */
class WorkspaceStateTest {

    @Test
    void sceneIsTheFrontTabOfAFreshSession() {
        WorkspaceState s = WorkspaceFixtures.state();
        assertEquals(BuiltinWorkspaces.MODELING, s.current());
        assertEquals("Scene", "Scene");
        assertTrue(s.detached().isEmpty());
    }

    @Test
    void eitherTabMayLeaveButTheLastOneStays() {
        WorkspaceState s = WorkspaceFixtures.state();
        assertTrue(s.canDetach(BuiltinWorkspaces.MODELING));
        assertTrue(s.detach(BuiltinWorkspaces.MODELING), "Scene can pop out while UI stays");
        assertEquals(BuiltinWorkspaces.UI, s.current(), "the main window falls back to the remaining tab");
        assertFalse(s.canDetach(BuiltinWorkspaces.UI));
        assertFalse(s.detach(BuiltinWorkspaces.UI), "UI is now the only tab in the main window");
        assertEquals(Set.of(BuiltinWorkspaces.MODELING), s.detached());
        assertEquals(BuiltinWorkspaces.UI, s.current());
    }

    @Test
    void askingForADetachedTabRaisesItsWindow() {
        WorkspaceState s = WorkspaceFixtures.state();
        List<String> fired = new ArrayList<>();
        s.addListener(fired::add);
        s.set(BuiltinWorkspaces.UI);
        s.detach(BuiltinWorkspaces.UI);
        assertEquals(BuiltinWorkspaces.MODELING, s.current());
        assertTrue(s.takeFocusRequest(BuiltinWorkspaces.UI), "a fresh pop-out takes focus");
        assertFalse(s.takeFocusRequest(BuiltinWorkspaces.UI), "one-shot");

        s.set(BuiltinWorkspaces.UI); // e.g. opening a .omui from the Project Browser
        assertEquals(BuiltinWorkspaces.MODELING, s.current(), "a detached tab never comes back by itself");
        assertTrue(s.takeFocusRequest(BuiltinWorkspaces.UI));
        assertFalse(s.takeFocusRequest(BuiltinWorkspaces.MODELING));
        assertEquals(List.of(BuiltinWorkspaces.UI, BuiltinWorkspaces.MODELING), fired);
    }

    @Test
    void dockingBackChoosesWhetherTheTabTakesTheFront() {
        WorkspaceState s = WorkspaceFixtures.state();
        s.detach(BuiltinWorkspaces.UI);
        s.attach(BuiltinWorkspaces.UI, false); // the window's close button
        assertFalse(s.isDetached(BuiltinWorkspaces.UI));
        assertEquals(BuiltinWorkspaces.MODELING, s.current());

        s.detach(BuiltinWorkspaces.MODELING);
        s.attach(BuiltinWorkspaces.MODELING, true); // "Dock Back"
        assertEquals(BuiltinWorkspaces.MODELING, s.current());
        assertFalse(s.takeFocusRequest(BuiltinWorkspaces.MODELING), "no stale focus request for a window that is gone");
    }

    @Test
    void restoreAppliesTabAndDetachedTogetherAndNeverEmptiesTheMainWindow() {
        WorkspaceState s = WorkspaceFixtures.state();
        List<String> fired = new ArrayList<>();
        s.addListener(fired::add);
        s.restore(BuiltinWorkspaces.UI, List.of(BuiltinWorkspaces.UI));
        assertTrue(s.isDetached(BuiltinWorkspaces.UI));
        assertEquals(BuiltinWorkspaces.MODELING, s.current(), "a detached tab is never also the main window's front");
        assertEquals(1, fired.size());

        s.restore(BuiltinWorkspaces.UI, List.of(BuiltinWorkspaces.UI, BuiltinWorkspaces.MODELING));
        assertEquals(Set.of(BuiltinWorkspaces.MODELING), s.detached(), "a record detaching everything keeps its front tab");
        assertEquals(BuiltinWorkspaces.UI, s.current());

        s.restore(BuiltinWorkspaces.UI, List.of(BuiltinWorkspaces.MODELING));
        assertEquals(2, fired.size(), "an unchanged restore does not notify");
    }

    @Test
    void theTextureTabOnlyExistsWhileOpen() {
        WorkspaceState s = WorkspaceFixtures.state();
        assertFalse(s.isOpen(BuiltinWorkspaces.TEXTURE), "a fresh session has no Texture tab");
        assertTrue(s.descriptor(BuiltinWorkspaces.TEXTURE).orElseThrow().closable());
        assertFalse(s.descriptor(BuiltinWorkspaces.MODELING).orElseThrow().closable()
                || s.descriptor(BuiltinWorkspaces.UI).orElseThrow().closable());
        assertFalse(s.canDetach(BuiltinWorkspaces.TEXTURE));
        s.set(BuiltinWorkspaces.TEXTURE);
        assertEquals(BuiltinWorkspaces.MODELING, s.current(), "a closed tab cannot be brought forward");
        assertFalse(s.detach(BuiltinWorkspaces.TEXTURE));

        s.open(BuiltinWorkspaces.TEXTURE);
        assertTrue(s.isOpen(BuiltinWorkspaces.TEXTURE));
        assertEquals(BuiltinWorkspaces.TEXTURE, s.current(), "opening brings the tab to the front");

        assertTrue(s.close(BuiltinWorkspaces.TEXTURE));
        assertFalse(s.isOpen(BuiltinWorkspaces.TEXTURE));
        assertEquals(BuiltinWorkspaces.MODELING, s.current());
        assertFalse(s.close(BuiltinWorkspaces.TEXTURE), "closing twice changes nothing");
        assertFalse(s.close(BuiltinWorkspaces.UI), "fixed tabs never close");
        assertTrue(s.isOpen(BuiltinWorkspaces.UI));
    }

    @Test
    void anOpenTextureTabCountsTowardTheOneTabStaysRule() {
        WorkspaceState s = WorkspaceFixtures.state();
        s.open(BuiltinWorkspaces.TEXTURE);
        assertTrue(s.detach(BuiltinWorkspaces.MODELING));
        assertTrue(s.detach(BuiltinWorkspaces.UI), "Texture keeps the main window occupied");
        assertEquals(BuiltinWorkspaces.TEXTURE, s.current());
        assertFalse(s.canDetach(BuiltinWorkspaces.TEXTURE));

        s.close(BuiltinWorkspaces.TEXTURE);
        assertFalse(s.isDetached(BuiltinWorkspaces.MODELING), "closing the last main-window tab docks Scene back");
        assertTrue(s.isDetached(BuiltinWorkspaces.UI));
        assertEquals(BuiltinWorkspaces.MODELING, s.current());
    }

    @Test
    void theEditorOnlyEndsWhenExplicitlyClosedAndReopensInTheMainWindow() {
        WorkspaceState s = WorkspaceFixtures.state();
        s.open(BuiltinWorkspaces.TEXTURE);
        assertTrue(s.detach(BuiltinWorkspaces.TEXTURE));
        assertTrue(s.takeFocusRequest(BuiltinWorkspaces.TEXTURE));
        assertEquals(BuiltinWorkspaces.MODELING, s.current());

        s.attach(BuiltinWorkspaces.TEXTURE, true); // its popped-out window's close button
        assertTrue(s.isOpen(BuiltinWorkspaces.TEXTURE), "closing the window does not end the editor");
        assertFalse(s.isDetached(BuiltinWorkspaces.TEXTURE));
        assertEquals(BuiltinWorkspaces.TEXTURE, s.current(), "it lands back at the front of the main window");

        s.detach(BuiltinWorkspaces.TEXTURE);
        assertTrue(s.close(BuiltinWorkspaces.TEXTURE)); // the popped-out tab button's close icon
        assertFalse(s.isDetached(BuiltinWorkspaces.TEXTURE));
        assertFalse(s.takeFocusRequest(BuiltinWorkspaces.TEXTURE), "no stale focus request for a closed tab");

        s.open(BuiltinWorkspaces.TEXTURE);
        assertFalse(s.isDetached(BuiltinWorkspaces.TEXTURE), "a reopened editor always starts in the main window");
        assertEquals(BuiltinWorkspaces.TEXTURE, s.current());
    }

    @Test
    void reopeningAnOpenTabRaisesIt() {
        WorkspaceState s = WorkspaceFixtures.state();
        s.open(BuiltinWorkspaces.TEXTURE);
        s.set(BuiltinWorkspaces.UI);
        s.open(BuiltinWorkspaces.TEXTURE); // e.g. "Edit Texture" while the tab is open behind UI
        assertEquals(BuiltinWorkspaces.TEXTURE, s.current());
        s.detach(BuiltinWorkspaces.TEXTURE);
        s.takeFocusRequest(BuiltinWorkspaces.TEXTURE);
        s.open(BuiltinWorkspaces.TEXTURE);
        assertTrue(s.takeFocusRequest(BuiltinWorkspaces.TEXTURE), "a popped-out editor raises its window");
    }

    @Test
    void restoreNeverOpensOrClosesTheEditor() {
        WorkspaceState s = WorkspaceFixtures.state();
        s.restore(BuiltinWorkspaces.TEXTURE, List.of(BuiltinWorkspaces.TEXTURE));
        assertFalse(s.isOpen(BuiltinWorkspaces.TEXTURE));
        assertEquals(BuiltinWorkspaces.MODELING, s.current(), "a closed front tab falls back to Scene");
        assertTrue(s.detached().isEmpty(), "a closed tab in the record is ignored");

        s.open(BuiltinWorkspaces.TEXTURE);
        assertFalse(s.isDetached(BuiltinWorkspaces.TEXTURE));

        s.restore(BuiltinWorkspaces.UI, List.of(BuiltinWorkspaces.TEXTURE, BuiltinWorkspaces.MODELING));
        assertTrue(s.isOpen(BuiltinWorkspaces.TEXTURE), "a restore never closes an open editor");
        assertEquals(Set.of(BuiltinWorkspaces.TEXTURE, BuiltinWorkspaces.MODELING), s.detached());
        assertEquals(BuiltinWorkspaces.UI, s.current());
    }

    @Test
    void aCloseGuardHoldsTheUsersCloseUntilItProceeds() {
        WorkspaceState s = WorkspaceFixtures.state();
        s.open(BuiltinWorkspaces.TEXTURE);
        List<Runnable> held = new ArrayList<>();
        s.setCloseGuard(BuiltinWorkspaces.TEXTURE, proceed -> {
            held.add(proceed);
            return false; // e.g. asking about unsaved textures
        });

        assertFalse(s.requestClose(BuiltinWorkspaces.TEXTURE));
        assertTrue(s.isOpen(BuiltinWorkspaces.TEXTURE), "still open while the guard asks");

        held.get(0).run(); // the user answered Save / Don't Save
        assertFalse(s.isOpen(BuiltinWorkspaces.TEXTURE));
    }

    @Test
    void aGuardThatAllowsClosesAtOnceAndFixedTabsNeverClose() {
        WorkspaceState s = WorkspaceFixtures.state();
        s.open(BuiltinWorkspaces.TEXTURE);
        s.setCloseGuard(BuiltinWorkspaces.TEXTURE, proceed -> true);
        assertTrue(s.requestClose(BuiltinWorkspaces.TEXTURE));
        assertFalse(s.isOpen(BuiltinWorkspaces.TEXTURE));

        assertFalse(s.requestClose(BuiltinWorkspaces.MODELING));
        assertTrue(s.isOpen(BuiltinWorkspaces.MODELING));
        s.setCloseGuard(BuiltinWorkspaces.TEXTURE, null);
        s.open(BuiltinWorkspaces.TEXTURE);
        assertTrue(s.requestClose(BuiltinWorkspaces.TEXTURE), "no guard: closes as before");
    }
}
