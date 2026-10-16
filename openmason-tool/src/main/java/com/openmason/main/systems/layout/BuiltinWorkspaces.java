package com.openmason.main.systems.layout;

/**
 * Ids of the built-in workspaces. The stored names stay {@code MODELING}/{@code UI}/{@code TEXTURE}
 * so older projects (their {@code uiEditor.workspace}/{@code detached} fields) and saved dock
 * layouts keep resolving.
 */
public final class BuiltinWorkspaces {

    /** The Scene workspace: models, textures and scenes; always there, the fallback front tab. */
    public static final String MODELING = "MODELING";

    /** The UI workspace: game UI screens and components. */
    public static final String UI = "UI";

    /** The Texture Editor: closable, present while it is open. */
    public static final String TEXTURE = "TEXTURE";

    private BuiltinWorkspaces() {
    }
}
