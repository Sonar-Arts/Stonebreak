package com.stonebreak.ui.worldHud;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.runtime.GameUiDocuments;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the world HUD's fidelity gate (#301): the shipped {@code ui/documents/world_hud.sbui}
 * on a {@link DocumentStage}, the frame published through {@code stonebreak:world.hud} exactly as the game
 * publishes it before the paint. Rects are each row's anchor recovered from its shown part. Not a test class.
 */
public final class DocumentWorldHudCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;

    public DocumentWorldHudCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentWorldHudCapture shipped() throws java.io.IOException {
        return new DocumentWorldHudCapture(GameUiDocuments.readScreen(WorldHud.DOCUMENT_ID));
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        WorldHudFixtures.Frame f = WorldHudFixtures.frame(c.variant(), vp.width(), vp.height());
        try (DocumentStage stage = new DocumentStage(shipped, vp.width(), vp.height(), vp.uiScale())) {
            stage.host.worldHud().publish(f.markers(), f.stealth());
            stage.paint(); // published right before the paint, as the game does: no settling frames
            return new MigrationGate.Capture(stage.raster.capture(), anchors(stage));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the world HUD document for " + c.id(), e);
        }
    }

    /** Each displayed row's anchor: the centred text's centre and baseline, the diamond's or the trap's centre. */
    static Map<String, float[]> anchors(DocumentStage stage) {
        Map<String, float[]> out = new LinkedHashMap<>();
        List<UiElement> rows = stage.view.instance().qAll(".marker");
        for (int i = 0; i < rows.size(); i++) {
            UiElement row = rows.get(i);
            float[] a = null;
            for (UiElement part : row.children()) {
                if (part.isCollapsed()) {
                    continue;
                }
                UiRect r = part.rect();
                if (part.hasClass("mark-text")) {
                    a = new float[]{r.x() + r.width() / 2f, r.y() + 100f, 0f, 0f};
                } else if (part.hasClass("diamond") || part.hasClass("trap")) {
                    a = new float[]{r.x() + r.width() / 2f, r.y() + r.height() / 2f, 0f, 0f};
                }
                if (a != null) {
                    break;
                }
            }
            if (a != null) {
                out.put("m" + i, a);
            }
        }
        UiElement label = stage.q("#stealth-label");
        if (label != null && !label.isCollapsed() && !stage.q("#stealth").isCollapsed()) {
            UiRect r = label.rect();
            out.put("stealth-label", new float[]{r.x() + r.width() / 2f, r.y() + 100f, 0f, 0f});
        }
        UiElement bar = stage.q("#reentry-bar");
        if (bar != null && !stage.q("#reentry").isCollapsed() && !stage.q("#stealth").isCollapsed()) {
            UiRect r = bar.rect();
            out.put("reentry-bar", new float[]{r.x(), r.y(), r.width(), r.height()});
        }
        return out;
    }
}
