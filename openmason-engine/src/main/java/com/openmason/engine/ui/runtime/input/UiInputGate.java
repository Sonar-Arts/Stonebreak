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
 *   <li>{@code TextField}: keyboard, text input and clipboard; supplementary-plane input unless
 *       its {@code inputFilter} restricts it to ASCII-range characters;</li>
 *   <li>a {@code TextField} in a locale whose scripts are typed through an IME (Chinese,
 *       Japanese, Korean): IME composition;</li>
 *   <li>any text in a right-to-left locale: RTL text and complex shaping;</li>
 *   <li>{@code draggable}: a pointer.</li>
 * </ul>
 * Focus, navigation and action hints need nothing beyond a keyboard, which every host has.
 */
public final class UiInputGate {

    /** One unmet need. */
    public record Block(InputCapability capability, String nodeId, String reason) {
    }

    private static final Set<String> IME_LANGUAGES = Set.of("zh", "ja", "ko");
    private static final Set<String> RTL_LANGUAGES = Set.of("ar", "he", "iw", "fa", "ur", "ps", "yi", "dv", "ug");
    private static final Set<String> NARROW_FILTERS = Set.of("ascii", "digits", "integer", "decimal");

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
            }
            if (IME_LANGUAGES.contains(locale.getLanguage())) {
                out.putIfAbsent(InputCapability.IME_COMPOSITION, where);
            }
        }
        if (RTL_LANGUAGES.contains(locale.getLanguage()) && ("Label".equals(type) || "TextField".equals(type))) {
            out.putIfAbsent(InputCapability.RTL_TEXT, where);
            out.putIfAbsent(InputCapability.COMPLEX_SHAPING, where);
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
