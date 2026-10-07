package com.openmason.main.systems.layout;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The main window's Scene | UI tabs: switching, popping tabs out, and the one-tab-stays rule. */
class WorkspaceStateTest {

    @Test
    void sceneIsTheFrontTabOfAFreshSession() {
        WorkspaceState s = new WorkspaceState();
        assertEquals(Workspace.MODELING, s.current());
        assertEquals("Scene", Workspace.MODELING.label());
        assertTrue(s.detached().isEmpty());
    }

    @Test
    void eitherTabMayLeaveButTheLastOneStays() {
        WorkspaceState s = new WorkspaceState();
        assertTrue(s.canDetach(Workspace.MODELING));
        assertTrue(s.detach(Workspace.MODELING), "Scene can pop out while UI stays");
        assertEquals(Workspace.UI, s.current(), "the main window falls back to the remaining tab");
        assertFalse(s.canDetach(Workspace.UI));
        assertFalse(s.detach(Workspace.UI), "UI is now the only tab in the main window");
        assertEquals(Set.of(Workspace.MODELING), s.detached());
        assertEquals(Workspace.UI, s.current());
    }

    @Test
    void askingForADetachedTabRaisesItsWindow() {
        WorkspaceState s = new WorkspaceState();
        List<Workspace> fired = new ArrayList<>();
        s.addListener(fired::add);
        s.set(Workspace.UI);
        s.detach(Workspace.UI);
        assertEquals(Workspace.MODELING, s.current());
        assertTrue(s.takeFocusRequest(Workspace.UI), "a fresh pop-out takes focus");
        assertFalse(s.takeFocusRequest(Workspace.UI), "one-shot");

        s.set(Workspace.UI); // e.g. opening a .omui from the Project Browser
        assertEquals(Workspace.MODELING, s.current(), "a detached tab never comes back by itself");
        assertTrue(s.takeFocusRequest(Workspace.UI));
        assertFalse(s.takeFocusRequest(Workspace.MODELING));
        assertEquals(List.of(Workspace.UI, Workspace.MODELING), fired);
    }

    @Test
    void dockingBackChoosesWhetherTheTabTakesTheFront() {
        WorkspaceState s = new WorkspaceState();
        s.detach(Workspace.UI);
        s.attach(Workspace.UI, false); // the window's close button
        assertFalse(s.isDetached(Workspace.UI));
        assertEquals(Workspace.MODELING, s.current());

        s.detach(Workspace.MODELING);
        s.attach(Workspace.MODELING, true); // "Dock Back"
        assertEquals(Workspace.MODELING, s.current());
        assertFalse(s.takeFocusRequest(Workspace.MODELING), "no stale focus request for a window that is gone");
    }

    @Test
    void restoreAppliesTabAndDetachedTogetherAndNeverEmptiesTheMainWindow() {
        WorkspaceState s = new WorkspaceState();
        List<Workspace> fired = new ArrayList<>();
        s.addListener(fired::add);
        s.restore(Workspace.UI, List.of(Workspace.UI));
        assertTrue(s.isDetached(Workspace.UI));
        assertEquals(Workspace.MODELING, s.current(), "a detached tab is never also the main window's front");
        assertEquals(1, fired.size());

        s.restore(Workspace.UI, List.of(Workspace.UI, Workspace.MODELING));
        assertEquals(Set.of(Workspace.MODELING), s.detached(), "a record detaching everything keeps its front tab");
        assertEquals(Workspace.UI, s.current());

        s.restore(Workspace.UI, List.of(Workspace.MODELING));
        assertEquals(2, fired.size(), "an unchanged restore does not notify");
    }
}
