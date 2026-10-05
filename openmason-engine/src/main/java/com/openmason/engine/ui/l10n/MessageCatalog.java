package com.openmason.engine.ui.l10n;

import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * The messages of one locale: key → {@link MessagePattern}. Immutable. Every pattern is
 * compiled when the catalog is built, so a broken translation fails at load time, naming its
 * key, instead of on the screen that first shows it.
 */
public final class MessageCatalog {

    private final Locale locale;
    private final Map<String, String> patterns;
    private final Map<String, MessagePattern> compiled;

    private MessageCatalog(Locale locale, Map<String, String> patterns, Map<String, MessagePattern> compiled) {
        this.locale = locale;
        this.patterns = Collections.unmodifiableMap(patterns);
        this.compiled = Collections.unmodifiableMap(compiled);
    }

    /** @throws MessageFormatException naming the key of the first pattern that does not parse */
    public static MessageCatalog of(Locale locale, Map<String, String> messages) {
        Objects.requireNonNull(locale, "locale");
        Map<String, String> patterns = new TreeMap<>();
        Map<String, MessagePattern> compiled = new TreeMap<>();
        for (Map.Entry<String, String> e : new TreeMap<>(messages).entrySet()) {
            String key = Objects.requireNonNull(e.getKey(), "key");
            String pattern = Objects.requireNonNull(e.getValue(), () -> "pattern of " + key);
            try {
                compiled.put(key, MessagePattern.compile(pattern));
            } catch (MessageFormatException ex) {
                throw new MessageFormatException("message '" + key + "' in " + locale.toLanguageTag() + ": "
                    + ex.getMessage(), ex.position());
            }
            patterns.put(key, pattern);
        }
        return new MessageCatalog(locale, patterns, compiled);
    }

    /**
     * Reads {@link Properties} syntax ({@code key = pattern}). The reader decides the charset;
     * catalogs ship as UTF-8.
     */
    public static MessageCatalog load(Locale locale, Reader reader) throws IOException {
        Properties p = new Properties();
        p.load(reader);
        Map<String, String> messages = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            messages.put(key, p.getProperty(key));
        }
        return of(locale, messages);
    }

    public Locale locale() {
        return locale;
    }

    /** @return the source pattern, or {@code null} when the key is absent */
    public String pattern(String key) {
        return patterns.get(key);
    }

    /** @return the compiled pattern, or {@code null} when the key is absent */
    public MessagePattern compiled(String key) {
        return compiled.get(key);
    }

    public Set<String> keys() {
        return patterns.keySet();
    }

    public boolean contains(String key) {
        return patterns.containsKey(key);
    }

    @Override
    public String toString() {
        return "MessageCatalog[" + locale.toLanguageTag() + ", " + patterns.size() + " keys]";
    }
}
