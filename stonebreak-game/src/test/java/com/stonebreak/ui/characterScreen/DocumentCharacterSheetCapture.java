package com.stonebreak.ui.characterScreen;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.UiRaster;
import com.stonebreak.ui.fidelity.RecordingServices;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.AbilityIconProvider;
import com.stonebreak.ui.runtime.providers.EntityPreviewProvider;
import com.stonebreak.ui.runtime.providers.PipsProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the character sheet's gate (#301): {@code ui/documents/character_sheet.sbui} opened as the
 * game opens it, bound to a real {@link CharacterScreen} in the fixture's state (its renderers' rules run its
 * actions), the GL preview stood in by nothing. Not a test class.
 */
public final class DocumentCharacterSheetCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;
    private final boolean oracleRects;

    public DocumentCharacterSheetCapture(SbuiArchive shipped) {
        this(shipped, false);
    }

    private DocumentCharacterSheetCapture(SbuiArchive shipped, boolean oracleRects) {
        this.shipped = shipped;
        this.oracleRects = oracleRects;
    }

    public static DocumentCharacterSheetCapture shipped() throws java.io.IOException {
        return new DocumentCharacterSheetCapture(GameUiDocuments.readScreen(CharacterScreen.DOCUMENT_ID));
    }

    /**
     * For re-freezing the reference after an intended visual change (the Daedalus Talents tab): the document's
     * pixels with {@link CharacterSheetFixtures#rects}' legacy-formula geometry, so the frozen rects stay an
     * independent oracle for {@code FLOAT_EXACT}.
     */
    public static DocumentCharacterSheetCapture reference() throws java.io.IOException {
        return new DocumentCharacterSheetCapture(GameUiDocuments.readScreen(CharacterScreen.DOCUMENT_ID), true);
    }

    public static DocumentStage stage(SbuiArchive sbui, CharacterScreen screen, int w, int h, float uiScale)
            throws Exception {
        RecordingServices services = new RecordingServices() {
            @Override
            public CharacterScreen characterSheet() {
                return screen;
            }

            @Override
            public int[] screenSize() {
                return new int[]{w, h};
            }
        };
        DocumentStage stage = new DocumentStage(sbui, w, h, uiScale, services, MultiplayerSession.Mode.SINGLEPLAYER,
            Map.of(AbilityIconProvider.ID, new AbilityIconProvider(), PipsProvider.ID, new PipsProvider(),
                EntityPreviewProvider.ID, DocumentStage.NO_GL));
        stage.host.characterSheet().poll();
        stage.settle();
        return stage;
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        int w = vp.width();
        int h = vp.height();
        try (UiRaster fonts = new UiRaster(w, h, vp.uiScale())) {
            CharacterScreen screen = CharacterSheetFixtures.screen(fonts.backend());
            CharacterSheetFixtures.prepare(screen, c.variant());
            try (DocumentStage stage = stage(shipped, screen, w, h, vp.uiScale())) {
                CharacterSheetFixtures.scroll(screen, c.variant());
                stage.host.characterSheet().poll();
                stage.settle();
                String hover = CharacterSheetFixtures.hover(c.variant());
                if (!hover.isEmpty()) {
                    UiRect r = element(stage, hover).rect();
                    stage.view.pointerMove(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
                    stage.settle();
                }
                stage.paint();
                Map<String, float[]> oracle = CharacterSheetFixtures.rects(screen, c.variant(), w, h);
                return new MigrationGate.Capture(stage.raster.capture(),
                    oracleRects ? oracle : rects(stage, oracle.keySet()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the character sheet document for " + c.id(), e);
        }
    }

    private static UiElement shown(List<UiElement> twins, int i) {
        UiElement on = twins.get(2 * i);
        return on.isCollapsed() ? twins.get(2 * i + 1) : on;
    }

    public static UiElement element(DocumentStage stage, String name) {
        var doc = stage.view.instance();
        String digits = name.replaceAll("[^0-9]", "");
        int i = digits.isEmpty() ? -1 : Integer.parseInt(digits);
        return switch (name.replaceAll("[0-9]", "")) {
            case "panel" -> stage.q("#panel");
            case "tab" -> doc.qAll(".sheet-tab").get(i);
            case "tile" -> doc.qAll(".tile").get(i);
            case "plus" -> shown(doc.qAll(".mini.plus"), i);
            case "talent" -> doc.qAll(".talent-slot").get(i);
            case "cls" -> doc.qAll(".cls-face").get(i);
            case "invest" -> shown(doc.qAll(".invest"), i);
            case "feat" -> doc.qAll(".feat-row").get(i);
            case "level" -> stage.q("#level-filter");
            case "general" -> stage.q("#general-filter");
            case "acquire" -> {
                UiElement live = stage.q("#acquire-live");
                yield live.isCollapsed() ? stage.q("#acquire-dead") : live;
            }
            default -> throw new IllegalArgumentException(name);
        };
    }

    static Map<String, float[]> rects(DocumentStage stage, Iterable<String> names) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String n : names) {
            UiElement el = element(stage, n);
            if (el != null && !el.isCollapsed()) {
                UiRect r = el.rect();
                out.put(n, new float[]{r.x(), r.y(), r.width(), r.height()});
            }
        }
        return out;
    }
}
