package com.openmason.main.systems.layout;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Which workspace tab is in front of the main window, and which tabs have been popped out into
 * their own windows; shared by the dock layout, the tab strip and the render loop.
 *
 * <p>Any tab may leave the main window as long as one stays: the last tab there cannot be
 * detached. The front tab is always one that is still in the main window; asking for a detached
 * tab raises its window instead of pulling it back.
 */
public final class WorkspaceState {

    private Workspace current = Workspace.MODELING;
    private final EnumSet<Workspace> detached = EnumSet.noneOf(Workspace.class);
    private final EnumSet<Workspace> focusRequests = EnumSet.noneOf(Workspace.class);
    private final List<Consumer<Workspace>> listeners = new CopyOnWriteArrayList<>();

    /** The tab in front of the main window (never a detached one). */
    public Workspace current() {
        return current;
    }

    /** True when the main window shows the UI workspace. */
    public boolean isUi() {
        return current == Workspace.UI;
    }

    public boolean isDetached(Workspace ws) {
        return detached.contains(ws);
    }

    /** The tabs living in their own windows. */
    public Set<Workspace> detached() {
        return detached.isEmpty() ? EnumSet.noneOf(Workspace.class) : EnumSet.copyOf(detached);
    }

    /** Whether {@code ws} may pop out now: it is in the main window and is not the last tab there. */
    public boolean canDetach(Workspace ws) {
        return ws != null && !detached.contains(ws) && Workspace.values().length - detached.size() > 1;
    }

    /** Brings {@code next} to the front; a detached tab raises its own window instead. */
    public void set(Workspace next) {
        if (next == null) {
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
     * Moves {@code ws} into its own window; the main window falls back to a remaining tab.
     *
     * @return false when it is the last tab in the main window (nothing changes)
     */
    public boolean detach(Workspace ws) {
        if (ws == null) {
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
        if (ws == null) {
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
     * empty keeps {@code front} (or Scene) in it.
     */
    public void restore(Workspace front, Collection<Workspace> detachedTabs) {
        EnumSet<Workspace> next = EnumSet.noneOf(Workspace.class);
        if (detachedTabs != null) {
            detachedTabs.stream().filter(java.util.Objects::nonNull).forEach(next::add);
        }
        Workspace f = front == null ? Workspace.MODELING : front;
        if (next.size() >= Workspace.values().length) {
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

    private Workspace firstAttached() {
        for (Workspace w : Workspace.values()) {
            if (!detached.contains(w)) {
                return w;
            }
        }
        return Workspace.MODELING; // unreachable: one tab always stays
    }

    private void fire() {
        listeners.forEach(l -> l.accept(current));
    }
}
