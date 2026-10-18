package com.openmason.main.systems.layout;

import com.openmason.api.workspace.WorkspaceDescriptor;
import com.openmason.api.workspace.WorkspaceLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Decides, per workspace, when its default dock layout is (re)built, and records which layout
 * version each user was given, per workspace id (#379). Replaces the per-builder version keys:
 * a workspace bumps {@link WorkspaceDescriptor#layoutVersion()} and every user gets exactly one
 * rebuild of that workspace, without touching the others.
 *
 * <p>A rebuild happens when the dockspace has no saved split arrangement, when the recorded
 * version is older than the descriptor's, or after {@link #requestReset}; checked once per session
 * per workspace (and again after a reset).
 */
public final class WorkspaceLayouts {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceLayouts.class);

    /** Where each workspace's last-built layout version is kept. */
    public interface VersionStore {
        /** The version this user was last given; 0 = never built. */
        int stored(String workspaceId);

        void store(String workspaceId, int version);
    }

    private final VersionStore store;
    private final IntPredicate hasSavedArrangement;
    private final Set<String> checked = new HashSet<>();
    private final Set<String> resets = new HashSet<>();

    /** Production gate: saved arrangements are read from ImGui's dock nodes. */
    public WorkspaceLayouts(VersionStore store) {
        this(store, dockspaceId -> {
            var node = imgui.internal.ImGui.dockBuilderGetNode(dockspaceId);
            return node != null && node.isSplitNode();
        });
    }

    /**
     * @param hasSavedArrangement whether a dockspace already holds a split (saved) arrangement
     */
    public WorkspaceLayouts(VersionStore store, IntPredicate hasSavedArrangement) {
        this.store = Objects.requireNonNull(store, "store");
        this.hasSavedArrangement = Objects.requireNonNull(hasSavedArrangement, "hasSavedArrangement");
    }

    /** The gate a workspace's dock calls after submitting its dockspace. */
    public WorkspaceLayout gate(WorkspaceDescriptor workspace) {
        return (dockspaceId, width, height) -> applyIfNeeded(workspace, dockspaceId, width, height);
    }

    /**
     * Rebuilds {@code workspace}'s default layout if this user needs it.
     *
     * @return true when it was rebuilt
     */
    public boolean applyIfNeeded(WorkspaceDescriptor workspace, int dockspaceId, float width, float height) {
        String id = workspace.id();
        boolean reset = resets.contains(id);
        if (checked.contains(id) && !reset) {
            return false;
        }
        checked.add(id);
        boolean saved = hasSavedArrangement.test(dockspaceId);
        int stored = store.stored(id);
        if (!LayoutRebuildDecision.shouldRebuild(saved, stored, workspace.layoutVersion(), reset)) {
            return false;
        }
        resets.remove(id);
        if (saved && !reset) {
            logger.info("Rebuilding the {} dock layout once (was version {}, now {}) so new windows have a home",
                    workspace.label(), stored, workspace.layoutVersion());
        } else {
            logger.info("Building the default {} dock layout", workspace.label());
        }
        workspace.dock().buildDefaultLayout(dockspaceId, width, height);
        store.store(id, workspace.layoutVersion());
        return true;
    }

    /** View → Reset Layout: {@code workspaceId}'s default layout is rebuilt on its next frame. */
    public void requestReset(String workspaceId) {
        resets.add(workspaceId);
    }

    /** Whether a reset of {@code workspaceId} is still pending. */
    public boolean isResetPending(String workspaceId) {
        return resets.contains(workspaceId);
    }
}
