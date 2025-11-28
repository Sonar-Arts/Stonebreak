package com.openmason.main;

/**
 * Represents the current state of the Open Mason application.
 * Used to control which UI interface is displayed.
 */
public enum ApplicationState {

    /**
     * Home screen is displayed - user selects a tool to open.
     */
    HOME_SCREEN,

    /**
     * Main interface is displayed - user is actively using a tool.
     */
    MAIN_INTERFACE,

    /**
     * Texture creator is displayed - user is creating/editing textures.
     */
    TEXTURE_CREATOR;

    /**
     * Check if the application is in the texture creator state.
     * @return true if in texture creator state
     */
    public boolean isTextureCreator() {
        return this == TEXTURE_CREATOR;
    }
}
