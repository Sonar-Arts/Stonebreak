package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The input part of a screen's migration gate (#288): which {@link InputCapability}s a document
 * needs, in a locale, that a host lacks. A host refuses to show a migrated screen while this
 * returns anything, and the screen stays on its legacy implementation; it is never shown with
 * input quietly missing.
 *
 * <p>Needs, per node (slot content included):
 * <ul>
 *   <li>{@code TextField}: keyboard, text input and clipboard; supplementary-plane input and
 *       fallback fonts unless its {@code inputFilter} restricts it to ASCII-range characters (a
 *       player can type, or paste, any character, and the game font has no emoji or CJK glyphs:
 *       without fallback they would draw as boxes);</li>
 *   <li>any text in a locale written in a script outside Latin-1 (Cyrillic, Greek, CJK, Arabic,
 *       Hebrew, Indic, Thai, ...): fallback fonts;</li>
 *   <li>a {@code TextField} in a locale whose scripts are typed through an IME (Chinese,
 *       Japanese, Korean): IME composition;</li>
 *   <li>any text in a right-to-left locale: RTL text and complex shaping;</li>
 *   <li>{@code draggable}: a pointer.</li>
 * </ul>
 * Focus, navigation and action hints need nothing beyond a keyboard, which every host has.
 *
 * <p>{@link InputCapability#ACCESSIBILITY_BRIDGE} is never derived: no legacy screen has a
 * platform screen-reader bridge either, so lacking one is not a regression a migration could
 * introduce. It stays in the enum for hosts that gain one.
 *
 * <p><b>Locale changes.</b> Needs depend on the locale, and a player can switch it while a
 * screen is open. Hosts re-check through a {@link Monitor}, which reports only when the answer
 * changes; a screen that becomes blocked must fall back to its legacy implementation.
 */
public final class UiInputGate {

    /** One unmet need. */
    public record Block(InputCapability capability, String nodeId, String reason) {
    }

    private static final Set<String> IME_LANGUAGES = Set.of("zh", "ja", "ko");
    private static final Set<String> RTL_LANGUAGES = Set.of("ar", "he", "iw", "fa", "ur", "ps", "yi", "dv", "ug");
    private static final Set<String> NARROW_FILTERS = Set.of("ascii", "digits", "integer", "decimal");
    /** Languages written in Latin script (Latin-1 or Latin Extended, which fonts commonly cover). */
    private static final Set<String> LATIN_LANGUAGES = Set.of("", "en", "de", "fr", "es", "it", "pt", "nl", "da", "sv",
        "no", "nb", "nn", "fi", "is", "ga", "ca", "eu", "gl", "af", "id", "ms", "sw", "tl", "fil", "et", "lv", "lt", "pl",
        "cs", "sk", "sl", "hr", "bs", "hu", "ro", "tr", "az", "vi", "sq", "mt", "cy", "eo", "la", "zu", "xh", "yo");

    private UiInputGate() {
    }

    /**
     * What {@code document}'s own tree needs in {@code locale}, each with the first node id that
     * needs it. Components are not expanded; {@link #needs(UiDocumentInstance, Locale)} checks the
     * running tree with every component instantiated.
     */
    public static Map<InputCapability, String> needs(OmuiArchive document, Locale locale) {
        Map<InputCapability, String> out = new LinkedHashMap<>();
        for (UiNode n : document.document().root().flatten()) {
            need(out, n.type(), n.props().get("inputFilter"), n.props().get("draggable"), n.id(), locale);
        }
        return out;
    }

    /** What a running instance needs, components included; keyed to element keys. */
    public static Map<InputCapability, String> needs(UiDocumentInstance ui, Locale locale) {
        Map<InputCapability, String> out = new LinkedHashMap<>();
        for (UiElement e : ui.elements()) {
            need(out, e.type(), e.prop("inputFilter"), e.prop("draggable"), e.key(), locale);
        }
        return out;
    }

    private static void need(Map<InputCapability, String> out, String type, UiValue inputFilter, UiValue draggable,
                             String where, Locale locale) {
        if ("TextField".equals(type)) {
            out.putIfAbsent(InputCapability.KEYBOARD, where);
            out.putIfAbsent(InputCapability.TEXT_INPUT, where);
            out.putIfAbsent(InputCapability.CLIPBOARD, where);
            String filter = inputFilter instanceof UiValue.Str s ? s.value() : "any";
            if (!NARROW_FILTERS.contains(filter)) {
                out.putIfAbsent(InputCapability.TEXT_INPUT_SUPPLEMENTARY, where);
                out.putIfAbsent(InputCapability.FONT_FALLBACK, where);
            }
            if (IME_LANGUAGES.contains(locale.getLanguage())) {
                out.putIfAbsent(InputCapability.IME_COMPOSITION, where);
            }
        }
        boolean text = "Label".equals(type) || "TextField".equals(type);
        if (RTL_LANGUAGES.contains(locale.getLanguage()) && text) {
            out.putIfAbsent(InputCapability.RTL_TEXT, where);
            out.putIfAbsent(InputCapability.COMPLEX_SHAPING, where);
        }
        if (text && !LATIN_LANGUAGES.contains(locale.getLanguage())) {
            out.putIfAbsent(InputCapability.FONT_FALLBACK, where);
        }
        if (draggable instanceof UiValue.Bool b && b.value()) {
            out.putIfAbsent(InputCapability.POINTER, where);
        }
    }

    /** Needs {@code host} does not meet; empty means the input gate is open. */
    public static List<Block> check(OmuiArchive document, Locale locale, Set<InputCapability> host) {
        return unmet(needs(document, locale), locale, host);
    }

    /** {@link #check} over a running instance (components included). */
    public static List<Block> check(UiDocumentInstance ui, Locale locale, Set<InputCapability> host) {
        return unmet(needs(ui, locale), locale, host);
    }

    /**
     * Re-checks a running screen when the locale (or the host's capabilities) may have changed.
     * Hosts call {@link #poll} on a locale change, or every frame: it re-evaluates only when an
     * input differs from the last call, so it is cheap to call often.
     */
    public static Monitor monitor(UiDocumentInstance ui, Set<InputCapability> host) {
        return new Monitor(ui, host);
    }

    /** See {@link #monitor}. Single-threaded, like the instance. */
    public static final class Monitor {
        private final UiDocumentInstance ui;
        private Set<InputCapability> host;
        private Locale locale;
        private int elements = -1;
        private List<Block> blocks = List.of();

        private Monitor(UiDocumentInstance ui, Set<InputCapability> host) {
            this.ui = java.util.Objects.requireNonNull(ui, "ui");
            this.host = Set.copyOf(host);
        }

        /** The host's capabilities changed (a controller connected, a bridge started). */
        public void setHost(Set<InputCapability> capabilities) {
            host = Set.copyOf(capabilities);
            elements = -1;
        }

        /**
         * Blocks for {@code current}; re-evaluated when the locale, the host or the element count
         * changed since the last call, else the previous answer.
         */
        public List<Block> poll(Locale current) {
            int n = ui.elementCount();
            if (!current.equals(locale) || n != elements) {
                locale = current;
                elements = n;
                blocks = List.copyOf(check(ui, current, host));
            }
            return blocks;
        }

        /** True when the last {@link #poll} found the screen blocked. */
        public boolean blocked() {
            return !blocks.isEmpty();
        }
    }

    private static List<Block> unmet(Map<InputCapability, String> needs, Locale locale, Set<InputCapability> host) {
        List<Block> out = new ArrayList<>();
        Set<InputCapability> have = host.isEmpty() ? EnumSet.noneOf(InputCapability.class) : EnumSet.copyOf(host);
        needs.forEach((cap, node) -> {
            if (!have.contains(cap)) {
                out.add(new Block(cap, node, cap + " is needed by " + node + " in " + locale.toLanguageTag()
                    + " and this host does not provide it"));
            }
        });
        return out;
    }
}
