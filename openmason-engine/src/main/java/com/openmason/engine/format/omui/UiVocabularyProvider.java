package com.openmason.engine.format.omui;

import java.util.Map;

/**
 * Names a game adds to the UI format (#384): the legacy bare widget types its documents were
 * written with before the type moved out of the engine into the game's namespace. A provider is
 * found with {@link java.util.ServiceLoader} (the game module {@code provides} it) or registered
 * with {@link UiVocabulary#register}; the engine itself names no game widget.
 *
 * <p>The painting side of the same game lives in {@code ui.theme.UiThemeProvider}; a game
 * usually implements both on one class.
 */
public interface UiVocabularyProvider {

    /**
     * Legacy bare type → namespaced type, e.g. {@code ItemSlot → stonebreak:ItemSlot}. A document
     * may keep using the bare name: it validates and instantiates as the namespaced widget, with
     * no {@code providers} declaration, and is never rewritten.
     */
    Map<String, String> widgetAliases();
}
