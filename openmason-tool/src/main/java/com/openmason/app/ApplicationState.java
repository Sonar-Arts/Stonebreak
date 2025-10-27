package com.openmason.app;

/**
 * Represents the current state of the Open Mason application.
 * Used to control which UI interface is displayed.
 *
 * Follows KISS principle: Simple state machine with clear transitions.
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
     * Check if the application is in the Home screen state.
     * @return true if in Home screen state
     */
    public boolean isHomeScreen() {
        return this == HOME_SCREEN;
    }

    /**
     * Check if the application is in the main interface state.
     * @return true if in main interface state
     */
    public boolean isMainInterface() {
        return this == MAIN_INTERFACE;
    }

    /**
     * Check if the application is in the texture creator state.
     * @return true if in texture creator state
     */
    public boolean isTextureCreator() {
        return this == TEXTURE_CREATOR;
    }
}
