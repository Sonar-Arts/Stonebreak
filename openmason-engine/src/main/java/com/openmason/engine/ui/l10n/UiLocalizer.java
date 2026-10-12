package com.openmason.engine.ui.l10n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Where the UI runtime gets localized text: the current locale, the loaded
 * {@link MessageCatalog}s and a fallback locale. Mutable; owned by the UI thread.
 *
 * <p><b>Resolution.</b> A key is looked up in the catalog of the exact locale
 * (language-country-variant), then language-country, then language, then the same chain for the
 * fallback locale. The first catalog holding the key formats it, with that catalog's locale for
 * plural rules and number formats, so a {@code de} pattern reached from {@code de-AT} pluralises
 * as German. A key found nowhere is recorded in {@link #missingKeys()}.
 *
 * <p>{@link #revision()} increases whenever resolved text could change (locale, catalogs,
 * pseudo mode), so a runtime can re-measure text only when it must.
 */
public final class UiLocalizer {

    private static final Set<String> RTL_LANGUAGES = Set.of("ar", "he", "iw", "fa", "ur", "ps", "yi", "dv", "ug");

    private final Locale fallback;
    private final Map<Locale, MessageCatalog> catalogs = new HashMap<>();
    private final Set<String> missing = new LinkedHashSet<>();
    private Locale locale;
    private boolean pseudo;
    private int revision;

    /** Starts in {@code fallback}, with no catalogs. */
    public UiLocalizer(Locale fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.locale = fallback;
    }

    /** English locale and fallback, no catalogs: every lookup falls back. */
    public static UiLocalizer english() {
        return new UiLocalizer(Locale.ENGLISH);
    }

    /** Adds a catalog, replacing any earlier one of the same locale. */
    public void addCatalog(MessageCatalog catalog) {
        catalogs.put(Objects.requireNonNull(catalog, "catalog").locale(), catalog);
        revision++;
    }

    public Locale locale() {
        return locale;
    }

    public Locale fallbackLocale() {
        return fallback;
    }

    public void setLocale(Locale newLocale) {
        Objects.requireNonNull(newLocale, "locale");
        if (!newLocale.equals(locale)) {
            locale = newLocale;
            revision++;
        }
    }

    /** Increases whenever resolved text could change. */
    public int revision() {
        return revision;
    }

    /** The localized text, or empty when no catalog in the chain has {@code key}. */
    public Optional<String> resolve(String key, Map<String, Object> args) {
        MessageCatalog c = catalogFor(key);
        if (c == null) {
            missing.add(key);
            return Optional.empty();
        }
        String text = c.compiled(key).format(args == null ? Map.of() : args, c.locale());
        return Optional.of(pseudo ? PseudoLocalizer.transform(text) : text);
    }

    /** The localized text; else {@code fallback} when non-null; else the key itself. */
    public String text(String key, Map<String, Object> args, String fallbackText) {
        Optional<String> r = resolve(key, args);
        if (r.isPresent()) {
            return r.get();
        }
        String text = fallbackText != null ? fallbackText : key;
        return pseudo ? PseudoLocalizer.transform(text) : text;
    }

    public boolean has(String key) {
        return catalogFor(key) != null;
    }

    /** True when the current locale's language is written right to left. */
    public boolean isRightToLeft() {
        return RTL_LANGUAGES.contains(locale.getLanguage());
    }

    public void setPseudo(boolean on) {
        if (pseudo != on) {
            pseudo = on;
            revision++;
        }
    }

    public boolean pseudo() {
        return pseudo;
    }

    /** Keys requested that no catalog in the chain holds, in first-request order. */
    public Set<String> missingKeys() {
        return Collections.unmodifiableSet(missing);
    }

    private MessageCatalog catalogFor(String key) {
        for (Locale l : chain()) {
            MessageCatalog c = catalogs.get(l);
            if (c != null && c.contains(key)) {
                return c;
            }
        }
        return null;
    }

    /** The lookup order for the current locale, then the fallback. */
    List<Locale> chain() {
        LinkedHashSet<Locale> out = new LinkedHashSet<>();
        expand(locale, out);
        expand(fallback, out);
        return new ArrayList<>(out);
    }

    private static void expand(Locale l, Set<Locale> out) {
        String lang = l.getLanguage();
        String country = l.getCountry();
        String variant = l.getVariant();
        if (!variant.isEmpty()) {
            out.add(Locale.of(lang, country, variant));
        }
        if (!country.isEmpty()) {
            out.add(Locale.of(lang, country));
        }
        out.add(Locale.of(lang));
    }
}
