package com.openmason.api.workspace;

/**
 * The host's layout gate for one workspace: rebuilds the default arrangement when the dockspace has
 * none saved, when the workspace's {@link WorkspaceDescriptor#layoutVersion()} is newer than the
 * one this user was last given, or when a reset was requested. Call right after submitting the
 * dockspace.
 */
@FunctionalInterface
public interface WorkspaceLayout {

    /** @return true when the default layout was (re)built this call */
    boolean applyIfNeeded(int dockspaceId, float width, float height);
}
