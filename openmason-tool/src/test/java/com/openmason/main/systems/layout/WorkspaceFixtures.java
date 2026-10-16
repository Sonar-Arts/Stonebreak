package com.openmason.main.systems.layout;

import com.openmason.api.Contributions;
import com.openmason.api.workspace.WorkspaceDescriptor;

/** The three built-in workspaces as the built-in plugins register them, for state/tab-strip tests. */
public final class WorkspaceFixtures {

    private WorkspaceFixtures() {
    }

    /** Scene, UI and the closable Texture workspace, in tab order, with empty docks. */
    public static Contributions<WorkspaceDescriptor> builtins() {
        Contributions<WorkspaceDescriptor> c = new Contributions<>("workspace", WorkspaceDescriptor::id);
        c.register("test", WorkspaceDescriptor.builder(BuiltinWorkspaces.MODELING, "Scene").build());
        c.register("test", WorkspaceDescriptor.builder(BuiltinWorkspaces.UI, "UI").build());
        c.register("test", WorkspaceDescriptor.builder(BuiltinWorkspaces.TEXTURE, "Texture").closable(true)
                .minSize(1100f, 700f).build());
        return c;
    }

    public static WorkspaceState state() {
        return new WorkspaceState(builtins());
    }
}
