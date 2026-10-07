package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.masonry.MDropdown;
import com.openmason.engine.ui.masonry.MSlider;
import com.openmason.engine.ui.masonry.MWidget;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.DirtBackdropProvider;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the settings gate (#299): the shipped {@code settings} document on a
 * {@link DocumentStage} bound to a {@link SettingsFixtures} menu (values pinned, restored on close).
 * Parts are named as the legacy layout sink names them and found by the document's element names.
 * Not a test class.
 */
public final class DocumentSettingsCapture implements MigrationGate.Renderer {

    /** A stage plus the pinned values to restore when it closes. */
    static final class Stage extends DocumentStage {
        private final com.openmason.engine.format.omui.UiValue.Obj restore;

        Stage(SbuiArchive sbui, FidelityCase c, RecordingServices services,
              com.openmason.engine.format.omui.UiValue.Obj restore) throws Exception {
            super(sbui, c.viewport().width(), c.viewport().height(), c.viewport().uiScale(), services,
                MultiplayerSession.Mode.MENU, Map.of(DirtBackdropProvider.ID, new DirtBackdropProvider()));
            this.restore = restore;
        }

        @Override
        public void close() {
            try {
                super.close();
            } finally {
                SettingsFixtures.restore(restore);
            }
        }
    }

    static Stage stage(FidelityCase c) throws Exception {
        int w = c.viewport().width();
        int h = c.viewport().height();
        RecordingServices services = new RecordingServices();
        services.window = new int[]{w, h};
        LegacyUiRaster probe = new LegacyUiRaster(w, h, c.viewport().uiScale());
        com.openmason.engine.format.omui.UiValue.Obj before;
        try {
            before = SettingsFixtures.pin();
            services.settingsMenu = SettingsFixtures.menu(probe.backend(), c.variant(), w, h);
        } finally {
            probe.close();
        }
        // the probe raster put the old UI scale back: the stage's raster sets the case's again
        return new Stage(GameUiDocuments.readScreen("settings"), c, services, before);
    }

    /** Legacy part name to the document element that is that part. */
    static Map<String, String> elements(StateManager st) {
        Map<String, String> out = new LinkedHashMap<>();
        if (st.isUiScaleConfirmActive()) {
            for (String n : List.of("dialog", "keep", "revert")) {
                out.put(n, n);
            }
            return out;
        }
        CategoryState.SettingType[] rows = st.getSelectedCategory().getSettings();
        MDropdown open = st.openDropdown();
        if (open != null) {
            int r = LegacySettingsCapture.rowOf(st, open);
            out.put("row" + r, "row" + r + "-dropdown");
            for (int k = 0; k < open.items().length; k++) {
                out.put("item" + k, "row" + r + "-item" + k);
            }
            return out;
        }
        for (int i = 0; i < 6; i++) {
            out.put("category" + i, "category" + i);
        }
        out.put("viewport", "viewport");
        for (int i = 0; i < rows.length; i++) {
            MWidget w = st.widget(rows[i]);
            out.put("row" + i, "row" + i + (w instanceof MSlider ? "-slider" : w instanceof MDropdown ? "-dropdown" : "-button"));
        }
        out.put("apply", "apply");
        out.put("back", "back");
        if (st.getCurrentScrollMath().isScrollNeeded()) {
            out.put("scrollbar", "scrollbar-grab");
        }
        return out;
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        try (Stage stage = stage(c)) {
            Map<String, String> elements = elements(stage.services.settingsMenu.getStateManager());
            String hover = SettingsFixtures.hover(c.variant());
            if (!hover.isEmpty()) {
                UiRect r = stage.q("#" + elements.get(hover)).rect();
                stage.view.pointerMove(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
                stage.settle();
            }
            stage.paint();
            var image = stage.raster.capture();
            Map<String, float[]> rects = new LinkedHashMap<>();
            Map<String, float[]> pressed = new LinkedHashMap<>();
            Map<String, String> byElement = new LinkedHashMap<>();
            elements.forEach((part, name) -> {
                UiElement el = stage.q("#" + name);
                UiRect r = el.rect();
                float[] rect = {r.x(), r.y(), r.width(), r.height()};
                rects.put(part, rect);
                if (!LegacySettingsCapture.PANELS.contains(part)) {
                    pressed.put(name, rect);
                    byElement.put(name, part);
                }
            });
            Map<String, float[]> hits = new LinkedHashMap<>();
            stage.hits(pressed).forEach((name, hit) -> hits.put(byElement.get(name), hit));
            Map<String, String> actions = new LinkedHashMap<>();
            stage.actions(pressed).forEach((name, a) -> actions.put(byElement.get(name), a));
            return new MigrationGate.Capture(image, rects, hits, actions);
        } catch (Exception e) {
            throw new IllegalStateException("could not capture " + c.id(), e);
        }
    }
}
