package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.l10n.MessageCatalog;
import com.openmason.engine.ui.l10n.UiLocalizer;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Localized labels, plurals, fallbacks and re-measuring on a locale change (#288). */
class LocalizedTextTest {

    /** 8 px per character of the localized text, 16 px tall. */
    private static final ContentMeasurer TEXT = (el, w, wm, h, hm, scale, out) -> {
        out[0] = UiTexts.label(el).length() * 8f * scale;
        out[1] = 16f * scale;
    };

    private static UiLocalizer localizer() {
        UiLocalizer l = UiLocalizer.english();
        l.addCatalog(MessageCatalog.of(Locale.ENGLISH, Map.of(
            "ui.items", "{count, plural, one {# item} other {# items}}",
            "ui.resume", "Resume")));
        l.addCatalog(MessageCatalog.of(Locale.GERMAN, Map.of(
            "ui.items", "{count, plural, one {# Gegenstand} other {# Gegenstände}}",
            "ui.resume", "Fortsetzen")));
        return l;
    }

    private static OmuiArchive doc() {
        return screen("t:ui/l10n", box("root").style("width", 400).style("height", 100).style("align-items", "flex-start")
            .kids(node("count", "Label").prop("textKey", "ui.items")
                    .prop("textArgs", UiValue.Obj.sorted(Map.of("count", UiValue.of(3)))),
                node("resume", "Label").prop("textKey", "ui.resume").prop("text", "Resume"),
                node("missing", "Label").prop("textKey", "ui.nowhere").prop("text", "Fallback"),
                node("bare", "Label").prop("textKey", "ui.alsoNowhere")));
    }

    @Test
    void keysResolveWithArgumentsAndPluralsAndFallBackVisibly() {
        UiLocalizer l10n = localizer();
        try (UiDocumentInstance ui = UiDocumentInstance.instantiate(doc(),
            UiRuntimeContext.basic().withMeasurer(TEXT).withLocalizer(l10n))) {
            assertEquals("3 items", UiTexts.label(ui.find("count")));
            assertEquals("Fallback", UiTexts.label(ui.find("missing")), "the plain text is the fallback");
            assertEquals("ui.alsoNowhere", UiTexts.label(ui.find("bare")), "with no fallback the key shows");
            assertTrue(UiDocs.has(ui, UiRuntimeDiagnostic.Code.MISSING_TEXT_KEY));
            ui.find("count").setProp("textArgs", UiValue.Obj.sorted(Map.of("count", UiValue.of(1))));
            assertEquals("1 item", UiTexts.label(ui.find("count")));
        }
    }

    @Test
    void aLocaleChangeRemeasuresEveryLabel() {
        UiLocalizer l10n = localizer();
        try (UiDocumentInstance ui = UiDocumentInstance.instantiate(doc(),
            UiRuntimeContext.basic().withMeasurer(TEXT).withLocalizer(l10n))) {
            ui.setMetrics(UiMetrics.of(400, 100, 1));
            ui.update();
            assertEquals("Resume".length() * 8, ui.find("resume").rect().width(), 0.01);
            l10n.setLocale(Locale.GERMAN);
            UiDocumentInstance.UpdateStats stats = ui.update();
            assertTrue(stats.remeasured() >= 4, "every measured label re-measures");
            assertEquals("Fortsetzen".length() * 8, ui.find("resume").rect().width(), 0.01);
            assertEquals("3 Gegenstände", UiTexts.label(ui.find("count")));
            UiDocumentInstance.UpdateStats idle = ui.update();
            assertEquals(0, idle.remeasured(), "an unchanged locale costs nothing");
        }
    }

    @Test
    void pseudoLocalizationExposesLongStrings() {
        UiLocalizer l10n = localizer();
        try (UiDocumentInstance ui = UiDocumentInstance.instantiate(doc(),
            UiRuntimeContext.basic().withMeasurer(TEXT).withLocalizer(l10n))) {
            ui.setMetrics(UiMetrics.of(400, 100, 1));
            ui.update();
            float before = ui.find("resume").rect().width();
            l10n.setPseudo(true);
            ui.update();
            assertTrue(ui.find("resume").rect().width() >= before * 1.3f, "pseudo-localization grows text");
        }
    }

    @Test
    void textScaleGrowsTextWithoutGrowingTheLayout() {
        try (UiDocumentInstance ui = UiDocumentInstance.instantiate(doc(),
            UiRuntimeContext.basic().withMeasurer((el, w, wm, h, hm, scale, out) -> {
                out[0] = UiTexts.label(el).length() * 8f * scale * el.owner().preferences().textScale();
                out[1] = 16f * scale * el.owner().preferences().textScale();
            }).withLocalizer(localizer()))) {
            ui.setMetrics(UiMetrics.of(400, 100, 1));
            ui.update();
            float w = ui.find("resume").rect().width();
            ui.setPreferences(new UiPreferences(false, 1.5f));
            ui.update();
            assertEquals(w * 1.5f, ui.find("resume").rect().width(), 0.5);
            assertEquals(400, ui.root().rect().width(), 0.01, "the layout box itself keeps its size");
            assertEquals(3f, new UiPreferences(true, 9f).textScale(), "clamped");
        }
    }
}
