package com.openmason.main.systems.services;

/**
 * The application shell's side of the texture editor, for external drivers such as the MCP
 * server. Drivers work on documents (the editor's controller opens, focuses and closes them);
 * the shell only makes sure the Texture workspace is on screen. Closing the workspace is the
 * user's alone (its tab's close button or menu), so there is deliberately no close here.
 */
public interface TextureEditorPresenter {
    /**
     * Bring the Texture workspace forward, opening its tab first when it is not open (raises
     * its window when popped out). Never closes anything.
     */
    void show();

    /** Whether the Texture workspace tab is open. */
    boolean isVisible();

    /**
     * Push the active face document's pending canvas edits to its GPU texture (no-op for a
     * standalone texture).
     */
    void flush();
}
