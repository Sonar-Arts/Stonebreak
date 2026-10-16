package com.openmason.main.systems.services;

/**
 * Seam to the standalone Scripting window (mirrors {@link TextureEditorPresenter}): lets MCP tools
 * and the assistant open it without coupling to the window class.
 */
public interface ScriptingPresenter {
    void show();

    /** Show and load a saved script from the library by name. */
    void showWithScript(String scriptName);

    void close();

    boolean isVisible();
}
