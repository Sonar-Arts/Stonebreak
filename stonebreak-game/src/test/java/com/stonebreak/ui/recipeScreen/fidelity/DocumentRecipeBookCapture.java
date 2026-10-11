package com.stonebreak.ui.recipeScreen.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.input.InputHandler;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.RecordingServices;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.GameDrawProviders;
import com.stonebreak.ui.runtime.screens.ContainerDocument;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;

/**
 * The candidate side of the recipe book's gate (#301): {@code ui/documents/recipe_book.sbui} opened as the
 * game opens it, bound to a real {@link RecipeScreen} in the fixture's state (its actions run the legacy
 * rules), with the game's item-icon and elm providers (no block atlas: block icons stay blank, as on the
 * legacy raster). Not a test class.
 */
public final class DocumentRecipeBookCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;

    public DocumentRecipeBookCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentRecipeBookCapture shipped() throws java.io.IOException {
        return new DocumentRecipeBookCapture(GameUiDocuments.readScreen(RecipeScreen.DOCUMENT_ID));
    }

    /** A recipe book screen whose GL collaborators are mocks, open in {@code variant}'s state. */
    public static RecipeScreen screen(String variant) {
        RecipeScreen screen = new RecipeScreen(mock(UIRenderer.class), mock(InputHandler.class), mock(Renderer.class));
        RecipeBookFixtures.prepare(screen.state(), variant);
        return screen;
    }

    /** The document on a stage, serving {@code screen} in a {@code w}x{@code h} window. */
    public static DocumentStage stage(SbuiArchive sbui, RecipeScreen screen, int w, int h, float uiScale)
            throws Exception {
        RecordingServices services = new RecordingServices() {
            @Override
            public RecipeScreen recipeScreen() {
                return screen;
            }

            @Override
            public int[] screenSize() {
                return new int[]{w, h};
            }
        };
        AtomicReference<DocumentStage> holder = new AtomicReference<>();
        GameDrawProviders providers = new GameDrawProviders(null,
            () -> holder.get() == null ? null : holder.get().raster.backend().getMinecraftTypeface());
        DocumentStage stage = new DocumentStage(sbui, w, h, uiScale, services, MultiplayerSession.Mode.SINGLEPLAYER,
            providers.providers());
        holder.set(stage);
        stage.view.input().setSettings(ContainerDocument.SETTINGS);
        stage.host.recipeBook().poll();
        stage.settle();
        return stage;
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        RecipeScreen screen = screen(c.variant());
        try (DocumentStage stage = stage(shipped, screen, vp.width(), vp.height(), vp.uiScale())) {
            String hover = RecipeBookFixtures.hover(c.variant());
            if (!hover.isEmpty()) {
                UiRect r = element(stage, hover).rect();
                stage.view.pointerMove(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
                stage.settle();
            }
            stage.paint();
            return new MigrationGate.Capture(stage.raster.capture(), rects(stage));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the recipe book document for " + c.id(), e);
        }
    }

    /** The element of an oracle name: panel, search, catN, cellN, inN, out, prev, next. */
    public static UiElement element(DocumentStage stage, String name) {
        if (name.startsWith("cat")) {
            return stage.view.instance().qAll(".cat").get(Integer.parseInt(name.substring(3)));
        }
        if (name.startsWith("cell")) {
            return stage.view.instance().qAll(".grid-slot").get(Integer.parseInt(name.substring(4)));
        }
        if (name.startsWith("in") || name.equals("out")) {
            return stage.q("#hit-" + name);
        }
        if (name.equals("prev") || name.equals("next")) {
            UiElement on = stage.q("#" + name + "-on");
            return on != null && !on.isCollapsed() ? on : stage.q("#" + name + "-off");
        }
        return stage.q("#" + name);
    }

    static Map<String, float[]> rects(DocumentStage stage) {
        Map<String, float[]> out = new LinkedHashMap<>();
        put(out, "panel", stage.q("#panel"));
        put(out, "search", stage.q("#search"));
        List<UiElement> cats = stage.view.instance().qAll(".cat");
        for (int i = 0; i < cats.size(); i++) {
            put(out, "cat" + i, cats.get(i));
        }
        List<UiElement> cells = stage.view.instance().qAll(".grid-slot");
        for (int i = 0; i < cells.size(); i++) {
            put(out, "cell" + i, cells.get(i));
        }
        if (!stage.q("#detail").isCollapsed()) {
            for (int i = 0; i < 9; i++) {
                put(out, "in" + i, stage.q("#hit-in" + i));
            }
            put(out, "out", stage.q("#hit-out"));
            if (!stage.q("#variants").isCollapsed()) {
                put(out, "prev", element(stage, "prev"));
                put(out, "next", element(stage, "next"));
            }
        }
        return out;
    }

    private static void put(Map<String, float[]> out, String name, UiElement el) {
        if (el != null && !el.isCollapsed()) {
            UiRect r = el.rect();
            out.put(name, new float[]{r.x(), r.y(), r.width(), r.height()});
        }
    }
}
