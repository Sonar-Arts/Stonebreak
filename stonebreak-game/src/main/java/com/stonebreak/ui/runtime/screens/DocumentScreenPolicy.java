package com.stonebreak.ui.runtime.screens;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Which screens run as UI documents and which keep their legacy (hardcoded) implementation: the
 * per-screen rollback switch of the #297-#301 migrations.
 *
 * <p>A screen id runs as a document only when the game ships {@code ui/documents/<id>.sbui}
 * and the screen is not rolled back. Rollback, strongest first:
 * <ol>
 *   <li>{@link #setOverride} (a settings toggle or dev command at runtime);</li>
 *   <li>{@code -Dstonebreak.ui.legacy=<id,...>} or {@code =all};</li>
 *   <li>otherwise the document is used when it exists.</li>
 * </ol>
 * So until a migration ships its document, every screen is legacy by default, and a shipped
 * document can be switched off without a rebuild.
 */
public final class DocumentScreenPolicy {

    public static final String LEGACY_PROPERTY = "stonebreak.ui.legacy";

    private final Set<String> legacy;
    private final boolean allLegacy;
    private final Predicate<String> shipped;
    private final Map<String, Boolean> overrides = new ConcurrentHashMap<>();

    /**
     * @param legacyProperty value of {@value #LEGACY_PROPERTY} (null = none)
     * @param shipped        whether the game ships a document for an id
     */
    public DocumentScreenPolicy(String legacyProperty, Predicate<String> shipped) {
        String v = legacyProperty == null ? "" : legacyProperty.trim();
        this.allLegacy = v.equalsIgnoreCase("all") || v.equals("*");
        this.legacy = allLegacy ? Set.of() : Arrays.stream(v.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.shipped = shipped;
    }

    /** The game's policy: system property plus the classpath. */
    public static DocumentScreenPolicy fromSystem() {
        return new DocumentScreenPolicy(System.getProperty(LEGACY_PROPERTY), DocumentScreenPolicy::onClasspath);
    }

    static boolean onClasspath(String id) {
        return DocumentScreenPolicy.class.getResource("/" + resourcePath(id)) != null;
    }

    /** Classpath resource of a screen's export ({@code ui/documents/<id>.sbui}). */
    public static String resourcePath(String id) {
        return "ui/documents/" + id + ".sbui";
    }

    /** True when {@code id} should open as a document; false keeps the legacy screen. */
    public boolean useDocument(String id) {
        if (id == null || id.isBlank() || !shipped.test(id)) {
            return false;
        }
        Boolean forced = overrides.get(id);
        if (forced != null) {
            return !forced;
        }
        return !allLegacy && !legacy.contains(id);
    }

    /**
     * Forces {@code id} to its legacy implementation ({@code true}), to the document
     * ({@code false}), or back to the default ({@code null}). Takes effect the next time the
     * screen opens.
     */
    public void setOverride(String id, Boolean legacyScreen) {
        if (legacyScreen == null) {
            overrides.remove(id);
        } else {
            overrides.put(id, legacyScreen);
        }
    }
}
