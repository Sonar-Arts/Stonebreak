package com.openmason.engine.ui.l10n;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static com.openmason.engine.ui.l10n.PluralCategory.FEW;
import static com.openmason.engine.ui.l10n.PluralCategory.MANY;
import static com.openmason.engine.ui.l10n.PluralCategory.ONE;
import static com.openmason.engine.ui.l10n.PluralCategory.OTHER;
import static com.openmason.engine.ui.l10n.PluralCategory.TWO;
import static com.openmason.engine.ui.l10n.PluralCategory.ZERO;

/**
 * CLDR cardinal plural rules (v44) for the languages Stonebreak may ship, selected by
 * language. Unknown languages get the root rules: everything is {@link PluralCategory#OTHER}.
 *
 * <p>Operands follow UTS #35: {@code n} absolute value, {@code i} integer digits, {@code v}
 * visible fraction digit count, {@code f} visible fraction digits, {@code t} the same without
 * trailing zeros. A {@link BigDecimal} keeps its scale, so {@code 1.0} has {@code v = 1}
 * ("1.0 items" in English). A {@code double} carries no scale: an integral double counts as
 * an integer ({@code select(1.0) == ONE} in English), other values use their shortest decimal.
 * Compact-decimal exponents ({@code e}) and the {@code many} category they drive in fr/es/it/pt
 * are not modelled.
 */
public final class PluralRules {

    /** Plural operands of one number. */
    private record Operands(BigDecimal n, long i, int v, long f, long t) {

        static Operands of(BigDecimal value) {
            BigDecimal n = value.abs();
            int v = Math.max(0, n.scale());
            BigInteger whole = n.toBigInteger();
            long i = whole.bitLength() < 63 ? whole.longValue() : Long.MAX_VALUE;
            long f = 0;
            long t = 0;
            if (v > 0) {
                BigInteger frac = n.subtract(new BigDecimal(whole)).movePointRight(v).toBigInteger();
                f = frac.bitLength() < 63 ? frac.longValue() : Long.MAX_VALUE;
                t = f;
                while (t != 0 && t % 10 == 0) {
                    t /= 10;
                }
            }
            return new Operands(n, i, v, f, t);
        }

        /** {@code n = k}: equal in value (1.0 counts). */
        boolean nIs(long k) {
            return n.compareTo(BigDecimal.valueOf(k)) == 0;
        }

        /** {@code n % m} as an exact decimal. */
        BigDecimal nMod(long m) {
            return n.remainder(BigDecimal.valueOf(m));
        }

        static boolean in(BigDecimal x, long lo, long hi) {
            if (x.signum() != 0 && x.stripTrailingZeros().scale() > 0) {
                return false; // ranges only contain integers
            }
            return x.compareTo(BigDecimal.valueOf(lo)) >= 0 && x.compareTo(BigDecimal.valueOf(hi)) <= 0;
        }

        static boolean in(long x, long lo, long hi) {
            return x >= lo && x <= hi;
        }
    }

    private static final Map<String, PluralRules> CACHE = new ConcurrentHashMap<>();

    private final String language;
    private final Function<Operands, PluralCategory> rule;
    private final Set<PluralCategory> categories;

    private PluralRules(String language, Function<Operands, PluralCategory> rule, PluralCategory... used) {
        this.language = language;
        this.rule = rule;
        EnumSet<PluralCategory> set = EnumSet.of(OTHER);
        Collections.addAll(set, used);
        this.categories = Collections.unmodifiableSet(set);
    }

    /** Rules for {@code locale}'s language (region only matters for {@code pt-PT}). */
    public static PluralRules forLocale(Locale locale) {
        String lang = locale == null ? "" : locale.getLanguage();
        String key = "pt".equals(lang) && "PT".equals(locale.getCountry()) ? "pt-PT" : lang;
        return CACHE.computeIfAbsent(key, PluralRules::create);
    }

    public String language() {
        return language;
    }

    /** Categories this language distinguishes; always contains {@link PluralCategory#OTHER}. */
    public Set<PluralCategory> categories() {
        return categories;
    }

    public PluralCategory select(double n) {
        if (Double.isNaN(n) || Double.isInfinite(n)) {
            return OTHER;
        }
        if (n == Math.rint(n) && Math.abs(n) < 9.0e15) {
            return select(BigDecimal.valueOf((long) n));
        }
        return select(BigDecimal.valueOf(n));
    }

    public PluralCategory select(BigDecimal n) {
        if (n == null) {
            return OTHER;
        }
        return rule.apply(Operands.of(n));
    }

    private static PluralRules create(String key) {
        return switch (key) {
            case "en", "de", "nl", "sv", "nb", "no", "nn", "fi", "et", "it", "ca", "gl" ->
                new PluralRules(key, o -> o.i == 1 && o.v == 0 ? ONE : OTHER, ONE);
            case "es", "el", "hu", "tr", "bg", "ka", "kk" -> new PluralRules(key, o -> o.nIs(1) ? ONE : OTHER, ONE);
            case "da" -> new PluralRules(key,
                o -> o.nIs(1) || o.t != 0 && (o.i == 0 || o.i == 1) ? ONE : OTHER, ONE);
            case "pt", "fr" -> new PluralRules(key, o -> o.i == 0 || o.i == 1 ? ONE : OTHER, ONE);
            case "pt-PT" -> new PluralRules(key, o -> o.i == 1 && o.v == 0 ? ONE : OTHER, ONE);
            case "ro" -> new PluralRules(key, PluralRules::romanian, ONE, FEW);
            case "ru", "uk" -> new PluralRules(key, PluralRules::eastSlavic, ONE, FEW, MANY);
            case "be" -> new PluralRules(key, PluralRules::belarusian, ONE, FEW, MANY);
            case "pl" -> new PluralRules(key, PluralRules::polish, ONE, FEW, MANY);
            case "cs", "sk" -> new PluralRules(key, PluralRules::czech, ONE, FEW, MANY);
            case "ar" -> new PluralRules(key, PluralRules::arabic, ZERO, ONE, TWO, FEW, MANY);
            case "he", "iw" -> new PluralRules(key, PluralRules::hebrew, ONE, TWO);
            case "lt" -> new PluralRules(key, PluralRules::lithuanian, ONE, FEW, MANY);
            case "lv" -> new PluralRules(key, PluralRules::latvian, ZERO, ONE);
            default -> new PluralRules(key, o -> OTHER); // root, ja, zh, ko, th, vi, id, ...
        };
    }

    private static PluralCategory romanian(Operands o) {
        if (o.i == 1 && o.v == 0) {
            return ONE;
        }
        if (o.v != 0 || o.nIs(0) || !o.nIs(1) && Operands.in(o.nMod(100), 1, 19)) {
            return FEW;
        }
        return OTHER;
    }

    private static PluralCategory eastSlavic(Operands o) {
        if (o.v != 0) {
            return OTHER;
        }
        long m10 = o.i % 10;
        long m100 = o.i % 100;
        if (m10 == 1 && m100 != 11) {
            return ONE;
        }
        if (Operands.in(m10, 2, 4) && !Operands.in(m100, 12, 14)) {
            return FEW;
        }
        return MANY;
    }

    private static PluralCategory belarusian(Operands o) {
        BigDecimal m10 = o.nMod(10);
        BigDecimal m100 = o.nMod(100);
        if (Operands.in(m10, 1, 1) && !Operands.in(m100, 11, 11)) {
            return ONE;
        }
        if (Operands.in(m10, 2, 4) && !Operands.in(m100, 12, 14)) {
            return FEW;
        }
        if (Operands.in(m10, 0, 0) || Operands.in(m10, 5, 9) || Operands.in(m100, 11, 14)) {
            return MANY;
        }
        return OTHER;
    }

    private static PluralCategory polish(Operands o) {
        if (o.i == 1 && o.v == 0) {
            return ONE;
        }
        if (o.v != 0) {
            return OTHER;
        }
        long m10 = o.i % 10;
        long m100 = o.i % 100;
        if (Operands.in(m10, 2, 4) && !Operands.in(m100, 12, 14)) {
            return FEW;
        }
        return MANY;
    }

    private static PluralCategory czech(Operands o) {
        if (o.v != 0) {
            return MANY;
        }
        if (o.i == 1) {
            return ONE;
        }
        return Operands.in(o.i, 2, 4) ? FEW : OTHER;
    }

    private static PluralCategory arabic(Operands o) {
        if (o.nIs(0)) {
            return ZERO;
        }
        if (o.nIs(1)) {
            return ONE;
        }
        if (o.nIs(2)) {
            return TWO;
        }
        BigDecimal m100 = o.nMod(100);
        if (Operands.in(m100, 3, 10)) {
            return FEW;
        }
        if (Operands.in(m100, 11, 99)) {
            return MANY;
        }
        return OTHER;
    }

    private static PluralCategory hebrew(Operands o) {
        if (o.i == 1 && o.v == 0 || o.i == 0 && o.v != 0) {
            return ONE;
        }
        return o.i == 2 && o.v == 0 ? TWO : OTHER;
    }

    private static PluralCategory lithuanian(Operands o) {
        BigDecimal m10 = o.nMod(10);
        BigDecimal m100 = o.nMod(100);
        if (Operands.in(m10, 1, 1) && !Operands.in(m100, 11, 19)) {
            return ONE;
        }
        if (Operands.in(m10, 2, 9) && !Operands.in(m100, 11, 19)) {
            return FEW;
        }
        return o.f != 0 ? MANY : OTHER;
    }

    private static PluralCategory latvian(Operands o) {
        BigDecimal m10 = o.nMod(10);
        BigDecimal m100 = o.nMod(100);
        long f10 = o.f % 10;
        long f100 = o.f % 100;
        if (Operands.in(m10, 0, 0) || Operands.in(m100, 11, 19) || o.v == 2 && Operands.in(f100, 11, 19)) {
            return ZERO;
        }
        if (Operands.in(m10, 1, 1) && !Operands.in(m100, 11, 11) || o.v == 2 && f10 == 1 && f100 != 11
            || o.v != 2 && f10 == 1) {
            return ONE;
        }
        return OTHER;
    }

    @Override
    public String toString() {
        return "PluralRules[" + language + " " + categories + "]";
    }
}
