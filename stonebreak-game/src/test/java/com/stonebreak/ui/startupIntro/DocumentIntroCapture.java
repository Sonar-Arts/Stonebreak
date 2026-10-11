package com.stonebreak.ui.startupIntro;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.RecordingServices;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.IntroSceneProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The candidate side of the intro's fidelity gate (#301): the shipped {@code ui/documents/intro.sbui} on
 * a {@link DocumentStage}, its providers and {@code stonebreak:screen.intro} data reading the same
 * {@link IntroFixtures} scene as {@link LegacyIntroCapture}. Not a test class.
 */
public final class DocumentIntroCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;

    public DocumentIntroCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentIntroCapture shipped() throws java.io.IOException {
        return new DocumentIntroCapture(GameUiDocuments.readScreen(SonarArtsIntroScreen.DOCUMENT_ID));
    }

    /** Services serving {@code scene} as the showing intro; skips are recorded. */
    public static RecordingServices services(IntroScene scene) {
        return new RecordingServices() {
            @Override
            public IntroScene introScene() {
                return scene;
            }

            @Override
            public String introSkip() {
                calls.add("stonebreak:screen.intro.skip");
                return null;
            }
        };
    }

    /** The document on a stage, showing {@code scene}. */
    public static DocumentStage stage(SbuiArchive sbui, IntroScene scene, int w, int h, float uiScale)
            throws Exception {
        return new DocumentStage(sbui, w, h, uiScale, services(scene), MultiplayerSession.Mode.MENU,
            Map.of(IntroSceneProvider.OCEAN_ID, IntroSceneProvider.ocean(() -> scene),
                IntroSceneProvider.SONAR_ID, IntroSceneProvider.sonar(() -> scene)));
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        IntroScene scene = IntroFixtures.scene(c.variant(), vp.width(), vp.height());
        try (DocumentStage stage = stage(shipped, scene, vp.width(), vp.height(), vp.uiScale())) {
            stage.paint();
            Map<String, float[]> rects = new LinkedHashMap<>();
            UiRect root = stage.rect("root");
            rects.put("window", new float[]{root.x(), root.y(), root.width(), root.height()});
            if (scene.reveal() > 0f) {
                UiRect r = stage.rect("logo");
                rects.put("logo", new float[]{r.x(), r.y(), r.width(), r.height()});
            }
            return new MigrationGate.Capture(stage.raster.capture(), rects);
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the intro document for " + c.id(), e);
        }
    }
}
