package com.stonebreak.ui.characterCreation.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.characterCreation.CharacterCreationScreen;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.UiRaster;
import com.stonebreak.ui.fidelity.RecordingServices;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.AbilityIconProvider;
import com.stonebreak.ui.runtime.providers.EntityPreviewProvider;
import com.stonebreak.ui.runtime.providers.WoodPanelProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of character creation's gate (#301): {@code ui/documents/character_creation.sbui} opened as
 * the game opens it, bound to a real {@link CharacterCreationScreen} in the fixture's state (its actions run the
 * legacy rules; its fonts measure the tab strip), with the Skia providers and the GL model preview stood in by
 * nothing (the frozen legacy captures had no renderer either). Not a test class.
 */
public final class DocumentCharacterCreationCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;
    private final boolean oracleRects;

    public DocumentCharacterCreationCapture(SbuiArchive shipped) {
        this(shipped, false);
    }

    private DocumentCharacterCreationCapture(SbuiArchive shipped, boolean oracleRects) {
        this.shipped = shipped;
        this.oracleRects = oracleRects;
    }

    /**
     * Re-freezing the reference after an intended visual change (the Daedalus Talents tab and Undo): the document's
     * pixels with the legacy-formula geometry of {@link CharacterCreationFixtures}, independent of the document.
     */
    public static DocumentCharacterCreationCapture reference() throws java.io.IOException {
        return new DocumentCharacterCreationCapture(GameUiDocuments.readScreen(CharacterCreationScreen.DOCUMENT_ID), true);
    }

    public static DocumentCharacterCreationCapture shipped() throws java.io.IOException {
        return new DocumentCharacterCreationCapture(GameUiDocuments.readScreen(CharacterCreationScreen.DOCUMENT_ID));
    }

    /** The document on a stage serving {@code screen} in a {@code w}x{@code h} window. */
    public static DocumentStage stage(SbuiArchive sbui, CharacterCreationScreen screen, int w, int h, float uiScale)
            throws Exception {
        RecordingServices services = new RecordingServices() {
            @Override
            public CharacterCreationScreen characterCreation() {
                return screen;
            }

            @Override
            public int[] screenSize() {
                return new int[]{w, h};
            }
        };
        DocumentStage stage = new DocumentStage(sbui, w, h, uiScale, services, MultiplayerSession.Mode.SINGLEPLAYER,
            Map.of(AbilityIconProvider.ID, new AbilityIconProvider(), WoodPanelProvider.ID, new WoodPanelProvider(),
                EntityPreviewProvider.ID, DocumentStage.NO_GL));
        stage.host.characterCreation().poll();
        stage.settle();
        return stage;
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        int w = vp.width();
        int h = vp.height();
        try (UiRaster fonts = new UiRaster(w, h, vp.uiScale())) {
            CharacterCreationScreen screen = new CharacterCreationScreen(fonts.backend());
            CharacterCreationFixtures.prepare(screen, c.variant());
            try (DocumentStage stage = stage(shipped, screen, w, h, vp.uiScale())) {
                String hover = CharacterCreationFixtures.hover(c.variant());
                if (!hover.isEmpty()) {
                    UiRect r = element(stage, hover).rect();
                    stage.view.pointerMove(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
                    stage.settle();
                }
                stage.paint();
                Map<String, float[]> rects;
                if (oracleRects) {
                    rects = new LinkedHashMap<>(CharacterCreationFixtures.rects(screen, c.variant(), w, h));
                    rects.putAll(CharacterCreationFixtures.footerButtons(screen,
                        new com.openmason.engine.ui.masonry.MasonryUI(fonts.backend()), w, h));
                } else {
                    rects = rects(stage, c.variant(), screen, w, h);
                }
                return new MigrationGate.Capture(stage.raster.capture(), rects);
            } finally {
                screen.dispose();
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the character creation document for " + c.id(), e);
        }
    }

    /** The displayed one of an on/off pair (enabled and disabled twins). */
    private static UiElement shown(List<UiElement> twins, int i) {
        UiElement on = twins.get(2 * i);
        return on.isCollapsed() ? twins.get(2 * i + 1) : on;
    }

    /** The element of an oracle name. */
    public static UiElement element(DocumentStage stage, String name) {
        var doc = stage.view.instance();
        String digits = name.replaceAll("[^0-9]", "");
        int i = digits.isEmpty() ? -1 : Integer.parseInt(digits);
        String kind = name.replaceAll("[0-9]", "");
        return switch (kind) {
            case "left", "right", "footer", "back", "next", "undo" -> stage.q("#" + name);
            case "tab" -> doc.qAll(".cc-tab").get(i);
            case "talent" -> doc.qAll(".talent-slot").get(i);
            case "bg" -> doc.qAll(".bg-row").get(i);
            case "tile" -> doc.qAll(".tile").get(i);
            case "plus" -> shown(doc.qAll(".mini.plus"), i);
            case "minus" -> shown(doc.qAll(".mini.minus"), i);
            case "cls" -> doc.qAll(".cls").get(i);
            case "spend" -> shown(doc.qAll(".spend"), i);
            case "invest" -> shown(doc.qAll(".invest"), i);
            case "mode" -> doc.qAll(".mode-btn").get(i);
            case "train" -> shown(doc.qAll(".train"), i);
            case "acquire" -> shown(doc.qAll(".acquire"), i);
            case "equip" -> doc.qAll((doc.find("pane_looks").isCollapsed() ? "#pane-CLOTHING" : "#pane-LOOKS")
                + " .equip").get(i);
            default -> throw new IllegalArgumentException(name);
        };
    }

    static Map<String, float[]> rects(DocumentStage stage, String variant, CharacterCreationScreen screen, int w, int h) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String name : CharacterCreationFixtures.rects(screen, variant, w, h).keySet()) {
            UiElement el = element(stage, name);
            if (el != null && !el.isCollapsed()) {
                UiRect r = el.rect();
                out.put(name, new float[]{r.x(), r.y(), r.width(), r.height()});
            }
        }
        for (String name : new String[]{"back", "next", "undo"}) {
            UiRect r = element(stage, name).rect();
            out.put(name, new float[]{r.x(), r.y(), r.width(), r.height()});
        }
        return out;
    }
}
