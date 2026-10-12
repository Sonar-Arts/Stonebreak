package com.openmason.main.systems.layout;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Which workspace tab is in front of the main window, which tabs are open, and which have been
 * popped out into their own windows; shared by the dock layout, the tab strip and the render loop.
 *
 * <p>Any open tab may leave the main window as long as one stays: the last tab there cannot be
 * detached. The front tab is always an open one that is still in the main window; asking for a
 * detached tab raises its window instead of pulling it back.
 *
 * <p>A {@link Workspace#closable() closable} tab exists only between {@link #open} and
 * {@link #close}, and only an explicit close ends it: closing its popped-out window docks it back
 * into the main window instead. It always (re)opens at the front of the main window.
 */
public final class WorkspaceState {

    private Workspace current = Workspace.MODELING;
    private final EnumSet<Workspace> open = EnumSet.noneOf(Workspace.class);
    private final EnumSet<Workspace> detached = EnumSet.noneOf(Workspace.class);
    private final EnumSet<Workspace> focusRequests = EnumSet.noneOf(Workspace.class);
    private final List<Consumer<Workspace>> listeners = new CopyOnWriteArrayList<>();
    private final java.util.EnumMap<Workspace, CloseGuard> closeGuards = new java.util.EnumMap<>(Workspace.class);

    /**
     * Lets a closable workspace hold a user's close until its unsaved work is dealt with.
     */
    @FunctionalInterface
    public interface CloseGuard {
        /**
         * @param proceed closes the tab; call it later when this guard took over
         * @return true to close now; false when the guard took over (e.g. it is asking the user)
         */
        boolean allowClose(Runnable proceed);
    }

    public WorkspaceState() {
        for (Workspace w : Workspace.values()) {
            if (!w.closable()) {
                open.add(w);
            }
        }
    }

    /** The tab in front of the main window (never a detached or closed one). */
    public Workspace current() {
        return current;
    }

    /** True when the main window shows the UI workspace. */
    public boolean isUi() {
        return current == Workspace.UI;
    }

    /** Whether {@code ws} has a tab: always for fixed tabs, between open and close for closable ones. */
    public boolean isOpen(Workspace ws) {
        return open.contains(ws);
    }

    public boolean isDetached(Workspace ws) {
        return detached.contains(ws);
    }

    /** The open tabs living in their own windows. */
    public Set<Workspace> detached() {
        return detached.isEmpty() ? EnumSet.noneOf(Workspace.class) : EnumSet.copyOf(detached);
    }

    /** Whether {@code ws} may pop out now: it is open, in the main window, and not the last tab there. */
    public boolean canDetach(Workspace ws) {
        return ws != null && open.contains(ws) && !detached.contains(ws) && attachedCount() > 1;
    }

    /** Brings {@code next} to the front; a detached tab raises its own window, a closed one does nothing. */
    public void set(Workspace next) {
        if (next == null || !open.contains(next)) {
            return;
        }
        if (detached.contains(next)) {
            focusRequests.add(next);
            return;
        }
        if (next != current) {
            current = next;
            fire();
        }
    }

    /**
     * Opens a closable tab at the front of the main window. An open (or fixed) tab is just
     * {@link #set}: brought forward where it is (a popped-out one raises its window).
     */
    public void open(Workspace ws) {
        if (ws == null) {
            return;
        }
        if (open.contains(ws)) {
            set(ws);
            return;
        }
        open.add(ws);
        current = ws;
        fire();
    }

    /** Installs (or, with null, removes) {@code ws}'s close guard, consulted by {@link #requestClose}. */
    public void setCloseGuard(Workspace ws, CloseGuard guard) {
        if (guard == null) {
            closeGuards.remove(ws);
        } else {
            closeGuards.put(ws, guard);
        }
    }

    /**
     * The user asked to close {@code ws} (its tab's close button or menu): closes it unless its
     * guard takes over, in which case the guard closes it later (or never, when the user cancels).
     *
     * @return true when the tab closed now
     */
    public boolean requestClose(Workspace ws) {
        if (ws == null || !ws.closable() || !open.contains(ws)) {
            return false;
        }
        CloseGuard guard = closeGuards.get(ws);
        if (guard != null && !guard.allowClose(() -> close(ws))) {
            return false;
        }
        return close(ws);
    }

    /**
     * Closes a closable tab (the user's explicit close: its tab's close button or menu, or the
     * MCP). When it was the last tab in the main window, a popped-out tab docks back so the main
     * window never ends up empty.
     *
     * @return false when {@code ws} is not closable or not open (nothing changes)
     */
    public boolean close(Workspace ws) {
        if (ws == null || !ws.closable() || !open.remove(ws)) {
            return false;
        }
        focusRequests.remove(ws);
        detached.remove(ws);
        if (attachedCount() == 0) {
            Workspace back = firstOpen();
            detached.remove(back);
            focusRequests.remove(back);
        }
        if (current == ws || !open.contains(current) || detached.contains(current)) {
            current = firstAttached();
        }
        fire();
        return true;
    }

    /**
     * Moves {@code ws} into its own window; the main window falls back to a remaining tab.
     *
     * @return false when it is closed or the last tab in the main window (nothing changes)
     */
    public boolean detach(Workspace ws) {
        if (ws == null || !open.contains(ws)) {
            return false;
        }
        if (detached.contains(ws)) {
            focusRequests.add(ws);
            return true;
        }
        if (!canDetach(ws)) {
            return false;
        }
        detached.add(ws);
        focusRequests.add(ws);
        if (current == ws) {
            current = firstAttached();
        }
        fire();
        return true;
    }

    /**
     * Puts {@code ws} back into the main window.
     *
     * @param bringToFront also make it the main window's front tab
     */
    public void attach(Workspace ws, boolean bringToFront) {
        if (ws == null || !open.contains(ws)) {
            return;
        }
        if (!detached.remove(ws)) {
            if (bringToFront) {
                set(ws);
            }
            return;
        }
        focusRequests.remove(ws);
        if (bringToFront) {
            current = ws;
        }
        fire();
    }

    /**
     * Restores a recorded session in one notification. A record that would leave the main window
     * empty keeps {@code front} (or Scene) in it. A restore neither opens nor closes a closable
     * tab: a closed one in the record is ignored, and a closed front tab falls back to Scene.
     */
    public void restore(Workspace front, Collection<Workspace> detachedTabs) {
        EnumSet<Workspace> next = EnumSet.noneOf(Workspace.class);
        if (detachedTabs != null) {
            detachedTabs.stream().filter(open::contains).forEach(next::add);
        }
        Workspace f = front == null || !open.contains(front) ? Workspace.MODELING : front;
        if (next.size() >= open.size()) {
            next.remove(f);
        }
        Workspace nextCurrent = next.contains(f) ? null : f;
        boolean changed = !next.equals(detached);
        detached.clear();
        detached.addAll(next);
        focusRequests.clear();
        if (nextCurrent == null) {
            nextCurrent = firstAttached();
        }
        changed |= nextCurrent != current;
        current = nextCurrent;
        if (changed) {
            fire();
        }
    }

    /** One-shot: {@code ws}'s detached window should take focus this frame. */
    public boolean takeFocusRequest(Workspace ws) {
        return focusRequests.remove(ws);
    }

    public void addListener(Consumer<Workspace> l) {
        listeners.add(l);
    }

    private int attachedCount() {
        int n = 0;
        for (Workspace w : open) {
            if (!detached.contains(w)) {
                n++;
            }
        }
        return n;
    }

    private Workspace firstAttached() {
        for (Workspace w : Workspace.values()) {
            if (open.contains(w) && !detached.contains(w)) {
                return w;
            }
        }
        return Workspace.MODELING; // unreachable: one tab always stays
    }

    private Workspace firstOpen() {
        for (Workspace w : Workspace.values()) {
            if (open.contains(w)) {
                return w;
            }
        }
        return Workspace.MODELING; // unreachable: fixed tabs never close
    }

    private void fire() {
        listeners.forEach(l -> l.accept(current));
    }
}
