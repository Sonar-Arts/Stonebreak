package com.stonebreak.ui.runtime.screens;

/**
 * Where the game's screen documents live: every screen is the export
 * {@code ui/documents/<id>.sbui} on the classpath (#297-#301; the legacy renderers were retired in
 * #302, so there is nothing else to fall back to).
 */
public final class ShippedDocuments {

    private ShippedDocuments() {
    }

    /** Classpath resource of a screen's export ({@code ui/documents/<id>.sbui}). */
    public static String resourcePath(String id) {
        return "ui/documents/" + id + ".sbui";
    }

    /** Whether the game ships a document for {@code id}. */
    public static boolean shipped(String id) {
        return id != null && !id.isBlank()
            && ShippedDocuments.class.getResource("/" + resourcePath(id)) != null;
    }
}
