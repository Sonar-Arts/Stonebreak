package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.DirtBackdropProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the multiplayer screens' fidelity gates (#299): the shipped
 * {@code multiplayer}, {@code host_world} and {@code join_world} documents on a {@link DocumentStage}
 * bound to the legacy screen objects in the state {@link LegacyMultiplayerCapture} puts them in. A
 * {@code -focused} case focuses the field by clicking at its right end (the caret then sits after the
 * text, where the legacy one is drawn). Not a test class.
 */
public final class DocumentMultiplayerCapture implements MigrationGate.Renderer {

    static String documentId(String screen) {
        return switch (screen) {
            case "multiplayer" -> "multiplayer";
            case "host-world" -> "host_world";
            case "join-world" -> "join_world";
            default -> throw new IllegalArgumentException(screen);
        };
    }

    static DocumentStage stage(FidelityCase c) throws Exception {
        RecordingServices services = new RecordingServices();
        LegacyUiRaster probe = new LegacyUiRaster(c.viewport().width(), c.viewport().height(), c.viewport().uiScale());
        try {
            switch (c.screen()) {
                case "host-world" -> services.hostWorld = LegacyMultiplayerCapture.host(probe.backend(), c.variant());
                case "join-world" -> {
                    services.joinWorld = LegacyMultiplayerCapture.join(probe.backend(), c.variant());
                    services.multiplayerBack = "stonebreak:screen.join-world.back";
                }
                default -> { }
            }
        } finally {
            probe.close();
        }
        SbuiArchive sbui = GameUiDocuments.readScreen(documentId(c.screen()));
        return new DocumentStage(sbui, c.viewport().width(), c.viewport().height(), c.viewport().uiScale(), services,
            MultiplayerSession.Mode.MENU, Map.of(DirtBackdropProvider.ID, new DirtBackdropProvider()));
    }

    static List<String> parts(FidelityCase c) {
        return switch (c.screen()) {
            case "multiplayer" -> List.of("host", "join", "back");
            case "host-world" -> {
                List<String> out = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    out.add("row" + i);
                }
                out.addAll(List.of("port", "start", "back"));
                yield out;
            }
            default -> List.of("host", "port", "username", "connect", "back");
        };
    }

    static List<String> fields(FidelityCase c) {
        return switch (c.screen()) {
            case "host-world" -> List.of("port");
            case "join-world" -> List.of("host", "port", "username");
            default -> List.of();
        };
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        try (DocumentStage stage = stage(c)) {
            if (c.variant().contains("-focused")) {
                String f = c.screen().equals("host-world") ? "port" : "host";
                UiRect r = stage.rect(f);
                stage.clickAt(r.right() - 2, r.y() + r.height() / 2f);
            }
            String hover = LegacyMultiplayerCapture.hover(c.variant());
            if (!hover.isEmpty()) {
                stage.hover(hover);
            }
            stage.paint();
            Map<String, float[]> rects = stage.rects(parts(c));
            Map<String, float[]> buttons = new LinkedHashMap<>(rects);
            fields(c).forEach(buttons::remove);
            return new MigrationGate.Capture(stage.raster.capture(), rects, stage.hits(buttons), stage.actions(buttons));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture " + c.id(), e);
        }
    }
}
