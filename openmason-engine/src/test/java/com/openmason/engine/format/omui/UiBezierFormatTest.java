package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Custom {@code cubic-bezier} timing on keys and transitions (#295). */
class UiBezierFormatTest {

    @Test
    void curvesMatchCssReferenceValues() {
        UiBezier ease = new UiBezier(0.25, 0.1, 0.25, 1);
        assertEquals(0, ease.apply(0));
        assertEquals(1, ease.apply(1));
        assertEquals(0.8024033877, ease.apply(0.5), 1e-6, "CSS 'ease' at 50%");
        UiBezier linear = new UiBezier(0, 0, 1, 1);
        for (double u = 0; u <= 1; u += 0.125) {
            assertEquals(u, linear.apply(u), 1e-7);
        }
        UiBezier back = new UiBezier(0.34, 1.56, 0.64, 1);
        double max = 0;
        for (double u = 0; u <= 1; u += 0.01) {
            max = Math.max(max, back.apply(u));
        }
        assertTrue(max > 1.05, "overshoot curves overshoot: " + max);
        assertEquals(ease, UiBezier.parse("cubic-bezier(0.25, 0.1, 0.25, 1)"));
        assertNull(UiBezier.parse("ease-out"));
        assertNotNull(new UiBezier(1.2, 0, 1, 1).problem(), "x must stay in [0, 1]");
    }

    @Test
    void keysAndTransitionsRoundTripTheirCurveAndKeepANamedFallback() throws Exception {
        UiBezier b = new UiBezier(0.34, 1.56, 0.64, 1);
        UiStyleSheet sheet = new UiStyleSheet("s", Map.of(), List.of(), List.of(new UiStyleSheet.StyleRule(".a",
            Map.of("opacity", UiValue.of(1)), List.of(new UiStyleSheet.StyleTransition("opacity", 0.3,
            UiEasing.EASE_OUT, 0, b, Map.of())), Map.of())), Map.of());
        UiAnimationClip clip = new UiAnimationClip("pop", 1, LoopMode.ONCE, List.of(new AnimTrack("root", "style:scale",
            List.of(new AnimKey(0, UiValue.of(0.5), UiEasing.EASE_OUT, b, Map.of()),
                new AnimKey(1, UiValue.of(1), UiEasing.LINEAR, Map.of())), Map.of())), List.of(), Map.of());
        OmuiArchive doc = OmuiArchive.of(UiManifest.create("t:ui/b", UiManifest.DocumentKind.SCREEN, "B"),
            new UiDocument(UiNode.of("root", "Box", List.of()), List.of("s"), null, null, Map.of()))
            .withStyle(sheet).withAnimation(clip);
        byte[] bytes = OmuiWriter.write(doc);
        OmuiArchive back = OmuiReader.read(bytes).archive();
        // The writer declares the curve's feature, so a reader that predates bezier refuses cleanly.
        assertEquals(UiFeatures.withInferred(doc), back);
        assertEquals(List.of(UiFeatures.MOTION), back.manifest().requires());
        String json = new String(OmuiWriter.entries(doc).get("animations/pop.anim.json").toArray(), StandardCharsets.UTF_8);
        assertTrue(json.replace(" ", "").contains("\"bezier\"") && json.replace(" ", "").contains("\"easing\":\"ease-out\""),
            "the named easing stays as the fallback older readers use: " + json);
    }

    @Test
    void invalidCurvesAreReportedWhereTheyAreWritten() {
        UiAnimationClip clip = new UiAnimationClip("bad", 1, LoopMode.ONCE, List.of(new AnimTrack("root", "style:scale",
            List.of(new AnimKey(0, UiValue.of(1), UiEasing.LINEAR, new UiBezier(-0.5, 0, 1, 1), Map.of())), Map.of())),
            List.of(), Map.of());
        OmuiArchive doc = OmuiArchive.of(UiManifest.create("t:ui/b", UiManifest.DocumentKind.SCREEN, "B"),
            new UiDocument(UiNode.of("root", "Box", List.of()), List.of(), null, null, Map.of())).withAnimation(clip);
        assertTrue(OmuiValidator.validate(doc).stream().anyMatch(d -> d.pointer().equals("/tracks/0/keys/0/bezier")));
    }
}
