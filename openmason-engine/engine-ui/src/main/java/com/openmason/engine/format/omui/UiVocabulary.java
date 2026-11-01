package com.openmason.engine.format.omui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The game-added names the format accepts (#384): every {@link UiVocabularyProvider} on the
 * module path (found once, lazily) plus any registered by a host that loads its game code in a
 * layer {@link ServiceLoader} does not see (an Open Mason plugin).
 *
 * <p>Without a provider a legacy alias is an unknown widget type, exactly as before it existed:
 * the engine ships no game names.
 */
public final class UiVocabulary {

    private static final Map<String, String> registered = new LinkedHashMap<>();
    private static volatile Map<String, String> aliases;

    private UiVocabulary() {
    }

    /** Adds {@code provider}'s names. Idempotent for the same aliases; a conflicting alias throws. */
    public static synchronized void register(UiVocabularyProvider provider) {
        merge(registered, provider.widgetAliases());
        aliases = null;
    }

    /** @return the namespaced type a legacy bare {@code type} stands for, or null */
    public static String widgetAlias(String type) {
        return type == null ? null : current().get(type);
    }

    /** Every legacy alias → namespaced type. */
    public static Map<String, String> widgetAliases() {
        return current();
    }

    /** {@code type}, or the namespaced type it is a legacy alias of. */
    public static String canonicalType(String type) {
        String alias = widgetAlias(type);
        return alias == null ? type : alias;
    }

    private static Map<String, String> current() {
        Map<String, String> a = aliases;
        if (a == null) {
            synchronized (UiVocabulary.class) {
                a = aliases;
                if (a == null) {
                    Map<String, String> all = new LinkedHashMap<>();
                    for (UiVocabularyProvider p : ServiceLoader.load(UiVocabularyProvider.class)) {
                        merge(all, p.widgetAliases());
                    }
                    merge(all, registered);
                    aliases = a = Map.copyOf(all);
                }
            }
        }
        return a;
    }

    private static void merge(Map<String, String> into, Map<String, String> add) {
        add.forEach((legacy, type) -> {
            if (UiWidgets.isNamespaced(legacy) || !UiWidgets.isNamespaced(type)) {
                throw new IllegalArgumentException("a widget alias maps a bare type to a namespaced one: "
                    + legacy + " -> " + type);
            }
            if (UiWidgets.isBuiltIn(legacy)) {
                throw new IllegalArgumentException("widget alias " + legacy + " shadows a built-in type");
            }
            String prev = into.putIfAbsent(legacy, type);
            if (prev != null && !prev.equals(type)) {
                throw new IllegalArgumentException("widget alias " + legacy + " already maps to " + prev);
            }
        });
    }
}
