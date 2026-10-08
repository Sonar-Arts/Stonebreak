package com.stonebreak.ui.inventoryScreen.core;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.items.Inventory;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.GameUiScriptServices;
import com.stonebreak.ui.runtime.contracts.SettingsContract;
import com.stonebreak.ui.runtime.providers.GameDrawProviders;
import com.stonebreak.ui.runtime.screens.ContainerDocument;
import com.stonebreak.ui.runtime.screens.UiLayer;
import com.stonebreak.ui.workbench.WorkbenchScreen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the workbench's fidelity gate (#300): the shipped export
 * {@code ui/documents/workbench.sbui}, opened as the game opens it ({@code GameUiDocuments.openBound}
 * against a {@link GameUiHost} whose open container is a real {@link WorkbenchInputManager} bound to
 * a {@link WorkbenchFixtures} table, Lua code-behind running, the game's draw providers) and painted
 * on the same {@link LegacyUiRaster} stage as {@link LegacyWorkbenchCapture}.
 *
 * <p>Rects are named as in {@link WorkbenchFixtures#rects}. Not a test class.
 */
public final class DocumentWorkbenchCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;

    public DocumentWorkbenchCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    /** What the game ships ({@code GameUiDocuments.readScreen("workbench")}). */
    public static DocumentWorkbenchCapture shipped() throws java.io.IOException {
        return new DocumentWorkbenchCapture(GameUiDocuments.readScreen(WorkbenchScreen.DOCUMENT_ID));
    }

    /** The document open on a raster stage, bound to a game host whose open container is a real crafting table. */
    public static final class Stage implements AutoCloseable {
        public final LegacyUiRaster raster;
        public final WorkbenchFixtures.Table table;
        public final WorkbenchController controller;
        public final WorkbenchInputManager input;
        public final GameUiHost host;
        public final List<String> requests = new ArrayList<>();
        final GameDrawProviders providers;
        public final UiDocumentView view;
        final MasonryUI masonry;
        final float scale;

        public Stage(SbuiArchive sbui, int w, int h, float scale, WorkbenchFixtures.Table table) throws Exception {
            raster = new LegacyUiRaster(w, h, scale);
            this.scale = scale;
            this.table = table;
            WorkbenchInputManager[] in = new WorkbenchInputManager[1];
            controller = WorkbenchFixtures.controller(table, in);
            input = in[0];
            host = new GameUiHost(services(input, table.inventory(), w, h, requests), MultiplayerSession.Mode.SINGLEPLAYER);
            providers = new GameDrawProviders(null, raster.backend()::getMinecraftTypeface);
            view = GameUiDocuments.openBound(sbui, Map.of(), raster.backend()::getMinecraftTypeface,
                providers.providers(), host.host(), null, new GameUiScriptServices(null), UiLayer.SCREEN);
            view.input().setSettings(ContainerDocument.SETTINGS);
            masonry = new MasonryUI(raster.backend());
            settle();
        }

        /** Scripts, bindings and layout caught up (the game's frame, minus painting). */
        public void settle() {
            for (int i = 0; i < 4; i++) {
                host.drain();
                GameUiDocuments.frame(view, 0.016, 0);
                view.layout(raster.width, raster.height, scale, 1f);
            }
        }

        public void paint() {
            GameUiDocuments.render(view, masonry, raster.width, raster.height, scale);
        }

        public UiElement q(String selector) {
            return view.instance().q(selector);
        }

        /** The element of a legacy-oracle name: craftN, output, mainN, hotN, recipes, craftall, sort, panel. */
        public UiElement element(String name) {
            if (name.startsWith("craftall")) {
                return q("#craftall");
            }
            if (name.startsWith("craft")) {
                return view.instance().qAll("#craft .grid-slot").get(Integer.parseInt(name.substring(5)));
            }
            if (name.startsWith("main")) {
                return view.instance().qAll("#main .grid-slot").get(Integer.parseInt(name.substring(4)));
            }
            if (name.startsWith("hot")) {
                return view.instance().qAll("#hotbar .grid-slot").get(Integer.parseInt(name.substring(3)));
            }
            return q("#" + name);
        }

        public float[] centre(String name) {
            UiRect r = element(name).rect();
            return new float[]{r.x() + r.width() / 2f, r.y() + r.height() / 2f};
        }

        /** The parity scripts' targets: {@code outside} (5,5), {@code panel} (just inside its corner), else a centre. */
        public float[] centreOrPoint(String name) {
            return switch (name) {
                case "outside" -> new float[]{5, 5};
                case "panel" -> {
                    UiRect r = q("#panel").rect();
                    yield new float[]{r.x() + 1, r.y() + 1};
                }
                default -> centre(name);
            };
        }

        /** A whole press and release of {@code button} at a point. */
        public void click(float x, float y, int button, int mods) {
            view.input().pointerMove(x, y);
            view.input().pointerDown(x, y, button, mods);
            view.input().pointerUp(x, y, button, mods);
            settle();
        }

        @Override
        public void close() {
            try {
                masonry.dispose();
                GameUiDocuments.close(view);
                providers.close();
            } finally {
                raster.close();
            }
        }
    }

    static GameUiHost.Services services(ContainerSlotInput screen, Inventory inv, int w, int h, List<String> requests) {
        return new GameUiHost.Services() {
            @Override public void resume() { }
            @Override public void openStatistics() { }
            @Override public void openGlossary() { }
            @Override public void openSettings() { }
            @Override public void quitToMenu() { }
            @Override public int resync() { return -1; }
            @Override public UiValue.Obj settings() {
                return SettingsContract.read(com.stonebreak.config.Settings.getInstance());
            }
            @Override public void applySettings(UiValue.Obj value) { }
            @Override public ContainerSlotInput openContainer() { return screen; }
            @Override public int[] screenSize() { return new int[]{w, h}; }
            @Override public Inventory inventory() { return inv; }
            @Override public String openRecipeBook() {
                requests.add("recipes");
                return null;
            }
        };
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        String hover = WorkbenchFixtures.hover(c.variant());
        try (Stage stage = new Stage(shipped, w, h, c.viewport().uiScale(),
                WorkbenchFixtures.table(WorkbenchFixtures.content(c.variant())))) {
            if (!hover.isEmpty()) {
                float[] at = stage.centre(hover);
                stage.view.input().pointerMove(at[0], at[1]);
                stage.settle();
            }
            stage.paint();
            return new MigrationGate.Capture(stage.raster.capture(), rects(stage));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the workbench document for " + c.id(), e);
        }
    }

    /** Rects named as in {@link WorkbenchFixtures#rects}; {@code craftall} only while it shows. */
    static Map<String, float[]> rects(Stage stage) {
        Map<String, float[]> out = new LinkedHashMap<>();
        put(out, "panel", stage.q("#panel"));
        for (int i = 0; i < 9; i++) {
            put(out, "craft" + i, stage.element("craft" + i));
        }
        put(out, "output", stage.q("#output"));
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            put(out, "main" + i, stage.element("main" + i));
        }
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            put(out, "hot" + i, stage.element("hot" + i));
        }
        put(out, "recipes", stage.q("#recipes"));
        put(out, "sort", stage.q("#sort"));
        if (!stage.input.getCraftingManager().getCraftingOutputSlot().isEmpty()) {
            put(out, "craftall", stage.q("#craftall"));
        }
        return out;
    }

    private static void put(Map<String, float[]> out, String name, UiElement el) {
        if (el != null) {
            UiRect r = el.rect();
            out.put(name, new float[]{r.x(), r.y(), r.width(), r.height()});
        }
    }
}
