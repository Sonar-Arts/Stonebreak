package com.openmason.engine.ui.theme;

import com.openmason.engine.format.omui.UiStyleProperties;
import com.openmason.engine.format.omui.UiVocabulary;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What the document painter draws named things with (#384): {@code -sb-surface} painters,
 * {@code -sb-symbol} painters and game widgets, each keyed by name. The engine's neutral entries
 * have bare names ({@code panel}, {@code button}, {@code inset}, {@code chevron-up}); a game's are
 * namespaced ({@code game:hotbar}) and may carry bare <em>aliases</em> so documents written before
 * the names were namespaced keep painting unchanged. An alias never shadows a neutral name.
 *
 * <p>Immutable; compose with {@link #toBuilder()}. Hosts get theirs through
 * {@code UiPaintHost.theme()}, by default {@link UiThemes#installed()}.
 */
public final class UiTheme {

    private final Map<String, SurfacePainter> surfaces;
    private final Map<String, String> surfaceAliases;
    private final Map<String, SymbolPainter> symbols;
    private final Map<String, String> symbolAliases;
    private final Map<String, UiThemeWidget> widgets;

    private UiTheme(Builder b) {
        surfaces = Collections.unmodifiableMap(new LinkedHashMap<>(b.surfaces));
        surfaceAliases = Collections.unmodifiableMap(new LinkedHashMap<>(b.surfaceAliases));
        symbols = Collections.unmodifiableMap(new LinkedHashMap<>(b.symbols));
        symbolAliases = Collections.unmodifiableMap(new LinkedHashMap<>(b.symbolAliases));
        widgets = Collections.unmodifiableMap(new LinkedHashMap<>(b.widgets));
    }

    public static Builder builder() {
        return new Builder(false);
    }

    /** A builder starting from this theme's entries. */
    public Builder toBuilder() {
        Builder b = new Builder(false);
        b.surfaces.putAll(surfaces);
        b.surfaceAliases.putAll(surfaceAliases);
        b.symbols.putAll(symbols);
        b.symbolAliases.putAll(symbolAliases);
        b.widgets.putAll(widgets);
        return b;
    }

    /** @return the surface named {@code name} (or aliased by it), or null */
    public SurfacePainter surface(String name) {
        SurfacePainter p = surfaces.get(name);
        return p != null || name == null ? p : surfaces.get(surfaceAliases.get(name));
    }

    /** @return the symbol named {@code name} (or aliased by it), or null */
    public SymbolPainter symbol(String name) {
        SymbolPainter p = symbols.get(name);
        return p != null || name == null ? p : symbols.get(symbolAliases.get(name));
    }

    /** @return the game widget of {@code type}, a namespaced type or its legacy bare alias, or null */
    public UiThemeWidget widget(String type) {
        return type == null ? null : widgets.get(UiVocabulary.canonicalType(type));
    }

    /** Canonical surface names, sorted (editor pickers); aliases are not listed. */
    public SortedSet<String> surfaceNames() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(surfaces.keySet()));
    }

    /** Canonical symbol names, sorted (editor pickers); aliases are not listed. */
    public SortedSet<String> symbolNames() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(symbols.keySet()));
    }

    /**
     * The values a picker offers for a {@code NAME} style property: {@code auto}/{@code none} and the
     * canonical surface names for {@code -sb-surface}, {@code none} and the symbol names for
     * {@code -sb-symbol}; empty for any other property.
     */
    public SortedSet<String> names(String property) {
        TreeSet<String> out = new TreeSet<>();
        switch (property) {
            case "-sb-surface" -> {
                out.add("auto");
                out.add("none");
                out.addAll(surfaces.keySet());
            }
            case "-sb-symbol" -> {
                out.add("none");
                out.addAll(symbols.keySet());
            }
            default -> {
            }
        }
        return Collections.unmodifiableSortedSet(out);
    }

    /** Bare alias → canonical surface name. */
    public Map<String, String> surfaceAliases() {
        return surfaceAliases;
    }

    /** Bare alias → canonical symbol name. */
    public Map<String, String> symbolAliases() {
        return symbolAliases;
    }

    public Collection<UiThemeWidget> widgets() {
        return widgets.values();
    }

    /** Collects entries. A contribution builder (handed to providers) only takes namespaced names. */
    public static final class Builder {

        private final boolean contribution;
        private final Map<String, SurfacePainter> surfaces = new LinkedHashMap<>();
        private final Map<String, String> surfaceAliases = new LinkedHashMap<>();
        private final Map<String, SymbolPainter> symbols = new LinkedHashMap<>();
        private final Map<String, String> symbolAliases = new LinkedHashMap<>();
        private final Map<String, UiThemeWidget> widgets = new LinkedHashMap<>();

        Builder(boolean contribution) {
            this.contribution = contribution;
        }

        public Builder surface(String name, SurfacePainter painter) {
            put(surfaces, surfaceAliases, checkName(name), painter, "surface");
            return this;
        }

        /** {@code -sb-surface: alias} paints {@code name}. */
        public Builder surfaceAlias(String alias, String name) {
            alias(surfaces, surfaceAliases, alias, name, "surface");
            return this;
        }

        public Builder symbol(String name, SymbolPainter painter) {
            put(symbols, symbolAliases, checkName(name), painter, "symbol");
            return this;
        }

        /** {@code -sb-symbol: alias} draws {@code name}. */
        public Builder symbolAlias(String alias, String name) {
            alias(symbols, symbolAliases, alias, name, "symbol");
            return this;
        }

        public Builder widget(UiThemeWidget widget) {
            if (widgets.putIfAbsent(widget.type(), widget) != null) {
                throw new IllegalArgumentException("widget " + widget.type() + " is already registered");
            }
            return this;
        }

        public UiTheme build() {
            return new UiTheme(this);
        }

        private String checkName(String name) {
            if (name == null || !UiStyleProperties.isThemeName(name) || UiStyleProperties.isReservedThemeName(name)) {
                throw new IllegalArgumentException("invalid theme name: " + name);
            }
            if (contribution && name.indexOf(':') < 0) {
                throw new IllegalArgumentException("a game's theme names are namespaced: " + name);
            }
            return name;
        }

        private static <T> void put(Map<String, T> into, Map<String, String> aliases, String name, T painter,
                                    String what) {
            if (painter == null) {
                throw new IllegalArgumentException(what + " " + name + " has no painter");
            }
            if (aliases.containsKey(name) || into.putIfAbsent(name, painter) != null) {
                throw new IllegalArgumentException(what + " " + name + " is already registered");
            }
        }

        private static <T> void alias(Map<String, T> names, Map<String, String> aliases, String alias, String name,
                                      String what) {
            if (alias == null || alias.indexOf(':') >= 0 || !UiStyleProperties.isThemeName(alias)
                || UiStyleProperties.isReservedThemeName(alias)) {
                throw new IllegalArgumentException("a " + what + " alias is a bare name: " + alias);
            }
            if (names.containsKey(alias)) {
                throw new IllegalArgumentException(what + " alias " + alias + " shadows a registered name");
            }
            String prev = aliases.putIfAbsent(alias, name);
            if (prev != null && !prev.equals(name)) {
                throw new IllegalArgumentException(what + " alias " + alias + " already maps to " + prev);
            }
        }
    }
}
