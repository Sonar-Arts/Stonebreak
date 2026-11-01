package com.openmason.engine.ui.l10n;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One-shot formatting of a {@link MessagePattern} source, with a small LRU of compiled
 * patterns so repeated formatting of the same text does not re-parse it.
 */
public final class MessageFormatter {

    private static final int CACHE_SIZE = 256;

    private static final Map<String, MessagePattern> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MessagePattern> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private MessageFormatter() {
    }

    /** @throws MessageFormatException when {@code pattern} does not parse */
    public static String format(String pattern, Map<String, Object> args, Locale locale) {
        return compiled(pattern).format(args, locale);
    }

    private static MessagePattern compiled(String pattern) {
        synchronized (CACHE) {
            MessagePattern p = CACHE.get(pattern);
            if (p == null) {
                p = MessagePattern.compile(pattern);
                CACHE.put(pattern, p);
            }
            return p;
        }
    }
}
