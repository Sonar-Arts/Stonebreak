package com.stonebreak.ui.furnace.core;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.items.Inventory;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.furnace.FurnaceDocument;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.GameUiScriptServices;
import com.stonebreak.ui.runtime.contracts.SettingsContract;
import com.stonebreak.ui.runtime.providers.GameDrawProviders;
import com.stonebreak.ui.runtime.screens.UiLayer;
import com.stonebreak.ui.support.UiTestFixtures;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the furnace's fidelity gate (#298): the shipped export
 * {@code ui/documents/furnace.sbui}, opened as the game opens it ({@code GameUiDocuments.openBound}
 * against a {@link GameUiHost} whose open container is a real {@link FurnaceInputManager}, Lua
 * code-behind running, the game's draw providers) and painted on the same {@link LegacyUiRaster}
 * stage as {@link LegacyFurnaceCapture}, with the same furnace state per variant.
 *
 * <p>Rects are named as in the legacy oracle: {@code panel}, the three furnace slots,
 * {@code main0..26} and {@code hot0..8}. Must live in this package for {@link FurnaceController#bind}.
 * Not a test class.
 */
public final class DocumentFurnaceCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;

    public DocumentFurnaceCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    /** What the game ships ({@code GameUiDocuments.readScreen("furnace")}). */
    public static DocumentFurnaceCapture shipped() throws java.io.IOException {
        return new DocumentFurnaceCapture(GameUiDocuments.readScreen(FurnaceDocument.ID));
    }

    /** A smelting manager that knows coal as fuel and iron ore as a recipe input (the slot rules ask it). */
    static SmeltingManager smelting() {
        SmeltingManager m = new SmeltingManager();
        m.registerFuel(BlockType.COAL_ORE, 1600);
        return m;
    }

    /** The document open on a raster stage, bound to a game host whose open container is a real furnace screen. */
    static final class Stage implements AutoCloseable {
        final LegacyUiRaster raster;
        final Inventory inventory;
        final FurnaceController controller;
        final FurnaceInputManager input;
        final FurnaceState furnace;
        final GameUiHost host;
        final GameDrawProviders providers;
        final UiDocumentView view;
        final MasonryUI masonry;
        final float scale;

        Stage(SbuiArchive sbui, int w, int h, float scale, FurnaceState furnace, Inventory inventory) throws Exception {
            raster = new LegacyUiRaster(w, h, scale);
            this.scale = scale;
            this.inventory = inventory;
            this.furnace = furnace;
            controller = new FurnaceController(null, inventory, null, smelting(), null);
            input = new FurnaceInputManager(null, inventory, controller);
            controller.setInputManager(input);
            controller.setSlotSink((pos, slots) -> { });
            controller.setFurnaceAt(p -> true);
            controller.bind(furnace);
            host = new GameUiHost(services(input, inventory, w, h), MultiplayerSession.Mode.SINGLEPLAYER);
            host.furnaceOpened(furnace);
            providers = new GameDrawProviders((type, x, y, size) -> { }, raster.backend()::getMinecraftTypeface);
            view = GameUiDocuments.openBound(sbui, Map.of(), raster.backend()::getMinecraftTypeface,
                providers.providers(), host.host(), null, new GameUiScriptServices(null), UiLayer.SCREEN);
            view.input().setSettings(FurnaceDocument.SETTINGS);
            masonry = new MasonryUI(raster.backend());
            settle();
        }

        /** Scripts, bindings and layout caught up (the game's frame, minus painting). */
        void settle() {
            for (int i = 0; i < 4; i++) {
                host.drain();
                GameUiDocuments.frame(view, 0.016, 0);
                view.layout(raster.width, raster.height, scale, 1f);
            }
        }

        void paint() {
            GameUiDocuments.render(view, masonry, raster.width, raster.height, scale);
        }

        UiElement q(String selector) {
            return view.instance().q(selector);
        }

        /** The slot element of a legacy-oracle name: ingredient, fuel, output, mainN, hotN. */
        UiElement slot(String name) {
            if (name.startsWith("main")) {
                return view.instance().qAll("#main .grid-slot").get(Integer.parseInt(name.substring(4)));
            }
            if (name.startsWith("hot")) {
                return view.instance().qAll("#hotbar .grid-slot").get(Integer.parseInt(name.substring(3)));
            }
            return q("#" + name);
        }

        /** A whole press and release of {@code button} at the centre of the named slot (or a point). */
        void click(float x, float y, int button, int mods) {
            view.input().pointerMove(x, y);
            view.input().pointerDown(x, y, button, mods);
            view.input().pointerUp(x, y, button, mods);
            settle();
        }

        float[] centre(String slot) {
            UiRect r = slot(slot).rect();
            return new float[]{r.x() + r.width() / 2f, r.y() + r.height() / 2f};
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

    static GameUiHost.Services services(ContainerSlotInput screen, Inventory inv, int w, int h) {
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
        };
    }

    /** The legacy capture's furnace for a variant ({@code unlit}, {@code lit}, {@code lit-hover-<slot>}). */
    static FurnaceState state(String variant) {
        boolean lit = variant.startsWith("lit");
        if (!lit) {
            return new FurnaceState(new BlockPos(0, 64, 0));
        }
        int cook = SmeltingManager.TICKS_PER_SMELT * 3 / 5;
        return FurnaceState.fromStateString(new BlockPos(0, 64, 0),
            FurnaceState.STATE_PREFIX + "state=Lit;burn=1200;burnTotal=1600;cook=" + cook);
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        String[] v = c.variant().split("-");
        String hover = v.length >= 3 && v[1].equals("hover") ? v[2] : "";
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (Stage stage = new Stage(shipped, w, h, c.viewport().uiScale(), state(c.variant()),
                UiTestFixtures.emptyInventory())) {
            if (!hover.isEmpty()) {
                float[] at = stage.centre(hover);
                stage.view.input().pointerMove(at[0], at[1]);
                stage.settle();
            }
            stage.paint();
            return new MigrationGate.Capture(stage.raster.capture(), rects(stage));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the furnace document for " + c.id(), e);
        }
    }

    /** Rects named as in {@link LegacyFurnaceCapture#rects}. */
    static Map<String, float[]> rects(Stage stage) {
        Map<String, float[]> out = new LinkedHashMap<>();
        put(out, "panel", stage.q("#panel"));
        for (String s : List.of("ingredient", "fuel", "output")) {
            put(out, s, stage.slot(s));
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            put(out, "main" + i, stage.slot("main" + i));
        }
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            put(out, "hot" + i, stage.slot("hot" + i));
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
