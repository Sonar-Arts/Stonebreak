package com.openmason.api.workspace;

import java.util.Objects;

/**
 * A top-level workspace of the editor shell: a tab of the main window with its own dockspace
 * (Scene, UI and Texture are the built-in ones). Contributed through
 * {@code PluginContext.workspaces()}; the tab order is registration order.
 *
 * @param id            stable id, stored in project files and the dock layout settings
 * @param label         the tab's caption
 * @param description   tab tooltip
 * @param closable      the tab exists only while opened on demand (and has a close button);
 *                      otherwise it is always there
 * @param minWidth      smallest size of the workspace's own window when popped out
 * @param minHeight     see {@code minWidth}
 * @param layoutVersion version of the default dock arrangement; bump it when the default gains or
 *                      moves a docked window so every user gets exactly one rebuild
 * @param dockspaceName the dockspace's ImGui string id (saved arrangements are keyed by it)
 * @param dock          the workspace's contents
 */
public record WorkspaceDescriptor(String id, String label, String description, boolean closable,
                                  float minWidth, float minHeight, int layoutVersion,
                                  String dockspaceName, WorkspaceDock dock) {

    public WorkspaceDescriptor {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("A workspace id cannot be blank");
        }
        Objects.requireNonNull(label, "label");
        description = description == null ? "" : description;
        if (layoutVersion < 1) {
            throw new IllegalArgumentException("Layout versions start at 1");
        }
        dockspaceName = dockspaceName == null || dockspaceName.isBlank() ? "OpenMasonWorkspace." + id : dockspaceName;
        dock = dock == null ? WorkspaceDock.EMPTY : dock;
    }

    /** A builder with the defaults: not closable, 720x480 minimum, layout version 1, empty dock. */
    public static Builder builder(String id, String label) {
        return new Builder(id, label);
    }

    public static final class Builder {
        private final String id;
        private final String label;
        private String description = "";
        private boolean closable;
        private float minWidth = 720f;
        private float minHeight = 480f;
        private int layoutVersion = 1;
        private String dockspaceName;
        private WorkspaceDock dock = WorkspaceDock.EMPTY;

        private Builder(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder closable(boolean closable) {
            this.closable = closable;
            return this;
        }

        public Builder minSize(float width, float height) {
            this.minWidth = width;
            this.minHeight = height;
            return this;
        }

        public Builder layoutVersion(int layoutVersion) {
            this.layoutVersion = layoutVersion;
            return this;
        }

        public Builder dockspaceName(String dockspaceName) {
            this.dockspaceName = dockspaceName;
            return this;
        }

        public Builder dock(WorkspaceDock dock) {
            this.dock = dock;
            return this;
        }

        public WorkspaceDescriptor build() {
            return new WorkspaceDescriptor(id, label, description, closable, minWidth, minHeight,
                    layoutVersion, dockspaceName, dock);
        }
    }
}
