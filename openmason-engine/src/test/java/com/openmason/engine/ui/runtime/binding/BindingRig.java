package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeContext;

import java.util.List;
import java.util.Map;

/** Shared fixtures for binding tests. Not a test class. */
final class BindingRig {

    private BindingRig() {
    }

    /** Pause menu (format golden sample) with its stone button component. */
    static UiRuntimeContext pauseContext() {
        return UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT)
            .withSource(UiDocumentSource.of(Map.of(UiSamples.BUTTON_ID, UiSamples.stoneButton()), Map.of()));
    }

    static OmuiArchive pause() {
        try {
            return UiSamples.pauseMenu();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The pause code-behind's {@code display_if}: a bool shows ({@code flex}) or collapses ({@code none}). */
    static final UiConverters PAUSE_CODE_BEHIND = UiConverters.of(Map.of("display_if",
        UiConverter.of(DataType.string(), v -> UiValue.of(v instanceof UiValue.Bool b && b.value() ? "flex" : "none"))));

    static OmuiArchive withData(OmuiArchive doc, String... hostApis) {
        return UiDocs.declare(doc, List.of(UiFeatures.DATA, UiFeatures.INPUT, UiFeatures.L10N), hostApis);
    }

    static UiValue.Obj obj(Object... kv) {
        java.util.Map<String, UiValue> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], UiDocs.value(kv[i + 1]));
        }
        return new UiValue.Obj(m);
    }

    /** First Label at or below {@code el}, depth first. */
    static UiElement label(UiElement el) {
        if ("Label".equals(el.type())) {
            return el;
        }
        for (UiElement c : el.children()) {
            UiElement l = label(c);
            if (l != null) {
                return l;
            }
        }
        return null;
    }
}
