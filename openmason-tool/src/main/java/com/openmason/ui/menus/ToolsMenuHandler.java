package com.openmason.ui.menus;

import imgui.ImGui;

/**
 * Tools menu handler.
 */
public class ToolsMenuHandler {

    private Runnable openTextureEditorCallback;

    /**
     * Set the callback for opening the texture editor.
     */
    public void setOpenTextureEditorCallback(Runnable callback) {
        this.openTextureEditorCallback = callback;
    }

    /**
     * Render the tools menu.
     */
    public void render() {
        if (!ImGui.beginMenu("Tools")) {
            return;
        }

        if (ImGui.menuItem("Texture Editor")) {
            if (openTextureEditorCallback != null) {
                openTextureEditorCallback.run();
            }
        }

        ImGui.endMenu();
    }
}
