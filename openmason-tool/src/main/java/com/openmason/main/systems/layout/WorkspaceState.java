package com.openmason.main.systems.layout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Which workspace is in front; shared by the dock layout, the menu bar tabs and the render loop. */
public final class WorkspaceState {

    private Workspace current = Workspace.MODELING;
    private final List<Consumer<Workspace>> listeners = new CopyOnWriteArrayList<>();

    public Workspace current() {
        return current;
    }

    public boolean isUi() {
        return current == Workspace.UI;
    }

    public void set(Workspace next) {
        if (next != null && next != current) {
            current = next;
            listeners.forEach(l -> l.accept(next));
        }
    }

    public void addListener(Consumer<Workspace> l) {
        listeners.add(l);
    }
}
