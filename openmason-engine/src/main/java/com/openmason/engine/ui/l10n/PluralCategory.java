package com.openmason.engine.ui.l10n;

import java.util.Locale;

/**
 * CLDR plural categories (UTS #35). Every language uses {@link #OTHER}; which of the rest a
 * language distinguishes is {@link PluralRules#categories()}.
 */
public enum PluralCategory {
    ZERO, ONE, TWO, FEW, MANY, OTHER;

    /** The keyword used in message patterns ({@code one}, {@code other}). */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @return the category for a pattern keyword, or {@code null} if it is not one */
    public static PluralCategory fromWire(String keyword) {
        if (keyword == null) {
            return null;
        }
        for (PluralCategory c : values()) {
            if (c.wire().equals(keyword)) {
                return c;
            }
        }
        return null;
    }
}
