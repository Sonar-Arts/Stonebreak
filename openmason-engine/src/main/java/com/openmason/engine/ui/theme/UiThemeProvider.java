package com.openmason.engine.ui.theme;

/**
 * A game's UI look (#384): its namespaced surfaces, symbols and widgets, and the bare legacy
 * names its documents used for them. Found with {@link java.util.ServiceLoader} (the game module
 * {@code provides} it) or registered with {@link UiThemes#register}. The engine contributes only
 * its neutral set ({@link UiThemes#neutral()}).
 */
public interface UiThemeProvider {

    /** Adds this game's entries; every name it registers must be namespaced. */
    void contribute(UiTheme.Builder theme);
}
