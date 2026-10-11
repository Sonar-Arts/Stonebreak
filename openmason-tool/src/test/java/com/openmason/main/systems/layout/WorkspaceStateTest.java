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

    @Test
    void theTextureTabOnlyExistsWhileOpen() {
        WorkspaceState s = new WorkspaceState();
        assertFalse(s.isOpen(Workspace.TEXTURE), "a fresh session has no Texture tab");
        assertTrue(Workspace.TEXTURE.closable());
        assertFalse(Workspace.MODELING.closable() || Workspace.UI.closable());
        assertFalse(s.canDetach(Workspace.TEXTURE));
        s.set(Workspace.TEXTURE);
        assertEquals(Workspace.MODELING, s.current(), "a closed tab cannot be brought forward");
        assertFalse(s.detach(Workspace.TEXTURE));

        s.open(Workspace.TEXTURE);
        assertTrue(s.isOpen(Workspace.TEXTURE));
        assertEquals(Workspace.TEXTURE, s.current(), "opening brings the tab to the front");

        assertTrue(s.close(Workspace.TEXTURE));
        assertFalse(s.isOpen(Workspace.TEXTURE));
        assertEquals(Workspace.MODELING, s.current());
        assertFalse(s.close(Workspace.TEXTURE), "closing twice changes nothing");
        assertFalse(s.close(Workspace.UI), "fixed tabs never close");
        assertTrue(s.isOpen(Workspace.UI));
    }

    @Test
    void anOpenTextureTabCountsTowardTheOneTabStaysRule() {
        WorkspaceState s = new WorkspaceState();
        s.open(Workspace.TEXTURE);
        assertTrue(s.detach(Workspace.MODELING));
        assertTrue(s.detach(Workspace.UI), "Texture keeps the main window occupied");
        assertEquals(Workspace.TEXTURE, s.current());
        assertFalse(s.canDetach(Workspace.TEXTURE));

        s.close(Workspace.TEXTURE);
        assertFalse(s.isDetached(Workspace.MODELING), "closing the last main-window tab docks Scene back");
        assertTrue(s.isDetached(Workspace.UI));
        assertEquals(Workspace.MODELING, s.current());
    }

    @Test
    void theEditorOnlyEndsWhenExplicitlyClosedAndReopensInTheMainWindow() {
        WorkspaceState s = new WorkspaceState();
        s.open(Workspace.TEXTURE);
        assertTrue(s.detach(Workspace.TEXTURE));
        assertTrue(s.takeFocusRequest(Workspace.TEXTURE));
        assertEquals(Workspace.MODELING, s.current());

        s.attach(Workspace.TEXTURE, true); // its popped-out window's close button
        assertTrue(s.isOpen(Workspace.TEXTURE), "closing the window does not end the editor");
        assertFalse(s.isDetached(Workspace.TEXTURE));
        assertEquals(Workspace.TEXTURE, s.current(), "it lands back at the front of the main window");

        s.detach(Workspace.TEXTURE);
        assertTrue(s.close(Workspace.TEXTURE)); // the popped-out tab button's close icon
        assertFalse(s.isDetached(Workspace.TEXTURE));
        assertFalse(s.takeFocusRequest(Workspace.TEXTURE), "no stale focus request for a closed tab");

        s.open(Workspace.TEXTURE);
        assertFalse(s.isDetached(Workspace.TEXTURE), "a reopened editor always starts in the main window");
        assertEquals(Workspace.TEXTURE, s.current());
    }

    @Test
    void reopeningAnOpenTabRaisesIt() {
        WorkspaceState s = new WorkspaceState();
        s.open(Workspace.TEXTURE);
        s.set(Workspace.UI);
        s.open(Workspace.TEXTURE); // e.g. "Edit Texture" while the tab is open behind UI
        assertEquals(Workspace.TEXTURE, s.current());
        s.detach(Workspace.TEXTURE);
        s.takeFocusRequest(Workspace.TEXTURE);
        s.open(Workspace.TEXTURE);
        assertTrue(s.takeFocusRequest(Workspace.TEXTURE), "a popped-out editor raises its window");
    }

    @Test
    void restoreNeverOpensOrClosesTheEditor() {
        WorkspaceState s = new WorkspaceState();
        s.restore(Workspace.TEXTURE, List.of(Workspace.TEXTURE));
        assertFalse(s.isOpen(Workspace.TEXTURE));
        assertEquals(Workspace.MODELING, s.current(), "a closed front tab falls back to Scene");
        assertTrue(s.detached().isEmpty(), "a closed tab in the record is ignored");

        s.open(Workspace.TEXTURE);
        assertFalse(s.isDetached(Workspace.TEXTURE));

        s.restore(Workspace.UI, List.of(Workspace.TEXTURE, Workspace.MODELING));
        assertTrue(s.isOpen(Workspace.TEXTURE), "a restore never closes an open editor");
        assertEquals(Set.of(Workspace.TEXTURE, Workspace.MODELING), s.detached());
        assertEquals(Workspace.UI, s.current());
    }
}
