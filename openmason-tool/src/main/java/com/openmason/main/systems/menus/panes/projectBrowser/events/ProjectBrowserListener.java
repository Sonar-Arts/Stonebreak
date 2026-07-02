package com.openmason.main.systems.menus.panes.projectBrowser.events;

/**
 * Listener for Project Browser selections — .OMO models and .OMT textures
 * discovered in the open project's folder.
 */
public interface ProjectBrowserListener {

    /** A .OMO model was selected (the viewport load is handled by the controller). */
    void onModelSelected(ModelSelectedEvent event);

    /** A .OMT texture was selected. */
    void onTextureSelected(TextureSelectedEvent event);
}
