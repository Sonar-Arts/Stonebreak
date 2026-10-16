package com.openmason.main.systems.layout;

import com.openmason.api.Contributions;
import com.openmason.api.workspace.WorkspaceDescriptor;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Which workspace tab is in front of the main window, which tabs are open, and which have been
 * popped out into their own windows; shared by the dock layout, the tab strip and the render loop.
 * Workspaces are the {@link WorkspaceDescriptor}s registered in the workspace contributions, named
 * by id; tab order is registration order, and a workspace registered later (a plugin's) joins in.
 *
 * <p>Any open tab may leave the main window as long as one stays: the last tab there cannot be
 * detached. The front tab is always an open one that is still in the main window; asking for a
 * detached tab raises its window instead of pulling it back.
 *
 * <p>A {@link WorkspaceDescriptor#closable() closable} tab exists only between {@link #open} and
 * {@link #close}, and only an explicit close ends it: closing its popped-out window docks it back
 * into the main window instead. It always (re)opens at the front of the main window.
 */
public final class WorkspaceState {

    private final Contributions<WorkspaceDescriptor> workspaces;
    private String current;
    private final Set<String> open = new LinkedHashSet<>();
    private final Set<String> detached = new LinkedHashSet<>();
    private final Set<String> focusRequests = new LinkedHashSet<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, CloseGuard> closeGuards = new HashMap<>();

    /**
     * Lets a closable workspace hold a user's close until its unsaved work is dealt with. A
     * workspace without one asks its dock ({@code WorkspaceDock.allowClose}).
     */
    @FunctionalInterface
    public interface CloseGuard {
        /**
         * @param proceed closes the tab; call it later when this guard took over
         * @return true to close now; false when the guard took over (e.g. it is asking the user)
         */
        boolean allowClose(Runnable proceed);
    }

    public WorkspaceState(Contributions<WorkspaceDescriptor> workspaces) {
        this.workspaces = workspaces;
        for (WorkspaceDescriptor w : workspaces.all()) {
            added(w);
        }
        workspaces.addListener(new Contributions.Listener<>() {
            @Override
            public void added(WorkspaceDescriptor item) {
                WorkspaceState.this.added(item);
            }

            @Override
            public void removed(WorkspaceDescriptor item) {
                WorkspaceState.this.removed(item);
            }
        });
    }

    /** The registered workspace {@code id}, if any. */
    public Optional<WorkspaceDescriptor> descriptor(String id) {
        return workspaces.find(id);
    }

    /** Every registered workspace, in tab order. */
    public List<WorkspaceDescriptor> workspaces() {
        return workspaces.all();
    }

    /** The tab in front of the main window (never a detached or closed one); null before any registers. */
    public String current() {
        return current;
    }

    /** True when the main window shows the UI workspace. */
    public boolean isUi() {
        return BuiltinWorkspaces.UI.equals(current);
    }

    /** Whether {@code ws} has a tab: always for fixed tabs, between open and close for closable ones. */
    public boolean isOpen(String ws) {
        return open.contains(ws);
    }

    public boolean isDetached(String ws) {
        return detached.contains(ws);
    }

    /** The open tabs living in their own windows, in tab order. */
    public Set<String> detached() {
        Set<String> out = new LinkedHashSet<>();
        for (WorkspaceDescriptor w : workspaces.all()) {
            if (detached.contains(w.id())) {
                out.add(w.id());
            }
        }
        return out;
    }

    /** Whether {@code ws} may pop out now: it is open, in the main window, and not the last tab there. */
    public boolean canDetach(String ws) {
        return ws != null && open.contains(ws) && !detached.contains(ws) && attachedCount() > 1;
    }

    /** Brings {@code next} to the front; a detached tab raises its own window, a closed one does nothing. */
    public void set(String next) {
        if (next == null || !open.contains(next)) {
            return;
        }
        if (detached.contains(next)) {
            focusRequests.add(next);
            return;
        }
        if (!next.equals(current)) {
            current = next;
            fire();
        }
    }

    /**
     * Opens a closable tab at the front of the main window. An open (or fixed) tab is just
     * {@link #set}: brought forward where it is (a popped-out one raises its window).
     */
    public void open(String ws) {
        if (ws == null || workspaces.find(ws).isEmpty()) {
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
    public void setCloseGuard(String ws, CloseGuard guard) {
        if (guard == null) {
            closeGuards.remove(ws);
        } else {
            closeGuards.put(ws, guard);
        }
    }

    /**
     * The user asked to close {@code ws} (its tab's close button or menu): closes it unless its
     * guard (or, without one, its dock) takes over, in which case that closes it later (or never,
     * when the user cancels).
     *
     * @return true when the tab closed now
     */
    public boolean requestClose(String ws) {
        if (ws == null || !closable(ws) || !open.contains(ws)) {
            return false;
        }
        CloseGuard guard = closeGuards.get(ws);
        if (guard == null) {
            guard = workspaces.find(ws).<CloseGuard>map(d -> d.dock()::allowClose).orElse(null);
        }
        if (guard != null && !guard.allowClose(() -> close(ws))) {
            return false;
        }
        return close(ws);
    }

    /**
     * Closes a closable tab (the user's explicit close: its tab's close button or menu, or the
     * app leaving the editor). When it was the last tab in the main window, a popped-out tab docks
     * back so the main window never ends up empty.
     *
     * @return false when {@code ws} is not closable or not open (nothing changes)
     */
    public boolean close(String ws) {
        if (ws == null || !closable(ws) || !open.remove(ws)) {
            return false;
        }
        focusRequests.remove(ws);
        detached.remove(ws);
        if (attachedCount() == 0) {
            String back = firstOpen();
            detached.remove(back);
            focusRequests.remove(back);
        }
        if (ws.equals(current) || !open.contains(current) || detached.contains(current)) {
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
    public boolean detach(String ws) {
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
        if (ws.equals(current)) {
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
    public void attach(String ws, boolean bringToFront) {
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
     * empty keeps {@code front} (or the default front tab) in it. A restore neither opens nor closes
     * a closable tab: a closed one in the record is ignored, and a closed or unknown front tab falls
     * back to Scene. Unknown ids (a workspace whose plugin is not installed) are ignored.
     */
    public void restore(String front, Collection<String> detachedTabs) {
        Set<String> next = new LinkedHashSet<>();
        if (detachedTabs != null) {
            detachedTabs.stream().filter(open::contains).forEach(next::add);
        }
        String f = front == null || !open.contains(front) ? defaultFront() : front;
        if (f != null && next.size() >= open.size()) {
            next.remove(f);
        }
        String nextCurrent = f == null || next.contains(f) ? null : f;
        boolean changed = !next.equals(detached);
        detached.clear();
        detached.addAll(next);
        focusRequests.clear();
        if (nextCurrent == null) {
            nextCurrent = firstAttached();
        }
        changed |= !java.util.Objects.equals(nextCurrent, current);
        current = nextCurrent;
        if (changed) {
            fire();
        }
    }

    /** One-shot: {@code ws}'s detached window should take focus this frame. */
    public boolean takeFocusRequest(String ws) {
        return focusRequests.remove(ws);
    }

    public void addListener(Consumer<String> l) {
        listeners.add(l);
    }

    /**
     * The stored workspace {@code stored} names, matched case-insensitively against the registered
     * ids; null when none matches.
     */
    public String find(String stored) {
        if (stored != null) {
            for (WorkspaceDescriptor w : workspaces.all()) {
                if (w.id().equalsIgnoreCase(stored.trim())) {
                    return w.id();
                }
            }
        }
        return null;
    }

    /** {@link #find} with unknown or absent values falling back to Scene, so old projects never move. */
    public String resolve(String stored) {
        String w = find(stored);
        return w != null ? w : defaultFront();
    }

    private boolean closable(String ws) {
        return workspaces.find(ws).map(WorkspaceDescriptor::closable).orElse(false);
    }

    private void added(WorkspaceDescriptor w) {
        if (!w.closable()) {
            open.add(w.id());
        }
        if (current == null && open.contains(w.id())) {
            current = w.id();
        }
    }

    private void removed(WorkspaceDescriptor w) {
        String id = w.id();
        open.remove(id);
        detached.remove(id);
        focusRequests.remove(id);
        closeGuards.remove(id);
        if (id.equals(current) || (current != null && detached.contains(current))) {
            current = firstAttached();
            fire();
        }
    }

    /** Scene when registered, else the first fixed tab. */
    private String defaultFront() {
        if (open.contains(BuiltinWorkspaces.MODELING)) {
            return BuiltinWorkspaces.MODELING;
        }
        return firstOpen();
    }

    private int attachedCount() {
        int n = 0;
        for (String w : open) {
            if (!detached.contains(w)) {
                n++;
            }
        }
        return n;
    }

    private String firstAttached() {
        for (WorkspaceDescriptor w : workspaces.all()) {
            if (open.contains(w.id()) && !detached.contains(w.id())) {
                return w.id();
            }
        }
        return defaultFront(); // unreachable while one tab always stays
    }

    private String firstOpen() {
        for (WorkspaceDescriptor w : workspaces.all()) {
            if (open.contains(w.id())) {
                return w.id();
            }
        }
        return null; // no workspace registered
    }

    private void fire() {
        listeners.forEach(l -> l.accept(current));
    }
}
