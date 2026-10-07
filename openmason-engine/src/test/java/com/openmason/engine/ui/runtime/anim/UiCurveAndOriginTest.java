package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiBezier;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.format.omui.UiStyleSheet.StyleRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.UiDocs.sheet;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.frame;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.num;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.run;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Custom bezier timing in the sampler and {@code transform-origin} / sprite pivots for scale and rotate (#295). */
class UiCurveAndOriginTest {

    static final UiBezier EASE = new UiBezier(0.25, 0.1, 0.25, 1);

    @Test
    void keyAndTransitionCurvesOverrideTheirNamedEasing() {
        UiDocumentInstance ui = run(screen("t:ui/c", box("root").kids(box("panel").name("panel").cls("p")),
            sheet("s"), new UiStyleSheet("t", Map.of(), List.of(), List.of(new StyleRule("#panel.moved",
                Map.of("translate-y", UiValue.of(100)), List.of(new UiStyleSheet.StyleTransition("translate-y", 1,
                UiEasing.LINEAR, 0, EASE, Map.of())), Map.of())), Map.of())));
        UiAnimationClip c = clip(new AnimKey(0, UiValue.of(0), UiEasing.STEP, EASE, Map.of()),
            new AnimKey(1, UiValue.of(100), UiEasing.LINEAR, Map.of()));
        ui.animator().play(c, k -> k, 1, null, null);
        frame(ui, 0.5);
        assertEquals(80.24033877, num(ui, "panel", "translate-x"), 1e-4, "the curve, not STEP");

        ui.find("panel").addClass("moved");
        ui.resolveStyles();
        frame(ui, 0.5);
        assertEquals(80.24033877, num(ui, "panel", "translate-y"), 1e-4, "transitions honour their curve too");

        ui.animator().tween(null, "panel", Map.of("opacity", UiValue.of(0)), 1, UiEasing.LINEAR,
            new UiAnimator.TweenOptions(0, UiClocks.UI, UiAnimator.Fill.HOLD, Map.of(), EASE), null);
        frame(ui, 0.5);
        assertEquals(1 - 0.8024033877, num(ui, "panel", "opacity"), 1e-4, "and tweens");
    }

    private static UiAnimationClip clip(AnimKey... keys) {
        return AnimDocs.clip("c", 1, LoopMode.ONCE, track("panel", "style:translate-x", keys));
    }

    /** A 100×40 card at (50, 50) whose sprite has its pivot at the bottom-left. */
    static OmuiArchive card() {
        return screen("t:ui/o", box("root").style("width", 400).style("height", 300).kids(
            node("card", "Image").prop("source", "sheet#corner").style("position", "absolute").style("left", 50)
                .style("top", 50).style("width", 100).style("height", 40).style("scale", 2)));
    }

    private static UiDocumentInstance laid(OmuiArchive doc, UiDocumentSource source) {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic()
            .withPixelGrid(UiRuntimeContext.NO_PIXEL_GRID).withSource(source));
        ui.setMetrics(UiMetrics.of(400, 300, 1));
        ui.update();
        return ui;
    }

    @Test
    void scaleAndRotateTurnAboutTheTransformOriginOrTheSpritePivot() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
        UiDocumentSource pivots = new UiDocumentSource() {
            @Override
            public OmuiArchive component(String id) {
                return null;
            }

            @Override
            public UiStyleSheet styleSheet(String id) {
                return null;
            }

            @Override
            public double[] spritePivot(String ref) {
                return "sheet#corner".equals(ref) ? new double[]{0, 1} : null;
            }
        };
        UiDocumentInstance centred = laid(card(), UiDocumentSource.EMPTY);
        assertEquals(new UiRect(0, 30, 200, 80), centred.find("card").paintBounds(), "no pivot known: the centre");

        UiDocumentInstance pivoted = laid(card(), pivots);
        assertEquals(new UiRect(50, 10, 200, 80), pivoted.find("card").paintBounds(),
            "grows up and right from the sprite's bottom-left pivot (50, 90)");

        UiElement card = pivoted.find("card");
        card.setStyle("transform-origin-x", UiValue.of("100%"));
        card.setStyle("transform-origin-y", UiValue.of(0));
        pivoted.update();
        assertEquals(new UiRect(-50, 50, 200, 80), card.paintBounds(), "an explicit origin beats the pivot: (150, 50)");
    }
}
