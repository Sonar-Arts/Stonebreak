package com.openmason.engine.ui.theme;

import com.openmason.engine.format.omui.UiVocabulary;
import com.openmason.engine.format.omui.UiVocabularyProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The themes of this process (#384): the engine's {@link #neutral()} entries plus every
 * {@link UiThemeProvider} on the module path (found once, lazily) and every provider a host
 * {@link #register registered} (an Open Mason plugin, whose module layer {@link ServiceLoader}
 * does not see). A provider class contributes once however it arrives.
 */
public final class UiThemes {

    private static final UiTheme NEUTRAL = NeutralTheme.build();
    private static final Map<String, UiThemeProvider> registered = new LinkedHashMap<>();
    private static volatile UiTheme installed;

    private UiThemes() {
    }

    /** The engine's own entries: no game names. */
    public static UiTheme neutral() {
        return NEUTRAL;
    }

    /** Neutral + every discovered and registered provider. */
    public static UiTheme installed() {
        UiTheme t = installed;
        if (t == null) {
            synchronized (UiThemes.class) {
                t = installed;
                if (t == null) {
                    Map<String, UiThemeProvider> all = new LinkedHashMap<>();
                    for (UiThemeProvider p : ServiceLoader.load(UiThemeProvider.class)) {
                        all.putIfAbsent(p.getClass().getName(), p);
                    }
                    registered.forEach(all::putIfAbsent);
                    installed = t = compose(NEUTRAL, all.values());
                }
            }
        }
        return t;
    }

    /**
     * Adds {@code provider} to {@link #installed()}; when it also names format aliases
     * ({@link UiVocabularyProvider}) those are registered too. Idempotent per provider class.
     */
    public static synchronized void register(UiThemeProvider provider) {
        if (registered.putIfAbsent(provider.getClass().getName(), provider) == null) {
            if (provider instanceof UiVocabularyProvider v) {
                UiVocabulary.register(v);
            }
            installed = null;
        }
    }

    /** {@code base} with {@code providers}' contributions, in order. */
    public static UiTheme compose(UiTheme base, Iterable<? extends UiThemeProvider> providers) {
        UiTheme.Builder b = base.toBuilder();
        for (UiThemeProvider p : providers) {
            UiTheme.Builder c = new UiTheme.Builder(true);
            p.contribute(c);
            UiTheme part = c.build();
            for (String n : part.surfaceNames()) {
                b.surface(n, part.surface(n));
            }
            part.surfaceAliases().forEach(b::surfaceAlias);
            for (String n : part.symbolNames()) {
                b.symbol(n, part.symbol(n));
            }
            part.symbolAliases().forEach(b::symbolAlias);
            part.widgets().forEach(b::widget);
        }
        return b.build();
    }
}
