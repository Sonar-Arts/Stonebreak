package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.multiplayerMenu.HostWorldScreen;
import com.stonebreak.ui.multiplayerMenu.JoinWorldScreen;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.DirtBackdropProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the multiplayer screens' fidelity gates (#299): the shipped
 * {@code multiplayer}, {@code host_world} and {@code join_world} documents on a {@link DocumentStage}
 * bound to the screen objects in the case's state, by the case's screen:
 *
 * <ul>
 *   <li>{@code multiplayer}: the menu; variants {@code plain}, {@code hover-host|join|back}.</li>
 *   <li>{@code host-world}: three worlds ({@link #WORLDS}) or {@code empty} or {@code nine} (only eight
 *       show), port 25565; {@code -focused} (port field with its caret), {@code -status} (an invalid
 *       port refused), {@code -hover-<part>} with parts {@code row0..row7}, {@code start}, {@code back}.</li>
 *   <li>{@code join-world}: fields filled ({@code filled}); {@code -focused} (host field), {@code -status}
 *       (an empty port refused), {@code -hover-connect|back}.</li>
 * </ul>
 *
 * A {@code -focused} case focuses the field by clicking at its right end (the caret then sits after
 * the text, where the legacy one was drawn). Not a test class.
 */
public final class DocumentMultiplayerCapture implements MigrationGate.Renderer {

    public static final List<String> WORLDS = List.of("Alpha", "Beta Base", "Gamma");

    static List<String> worlds(String variant) {
        if (variant.startsWith("empty")) {
            return List.of();
        }
        if (variant.startsWith("nine")) {
            List<String> out = new ArrayList<>();
            for (int i = 1; i <= 9; i++) {
                out.add("World " + i);
            }
            return out;
        }
        return WORLDS;
    }

    /** A host screen in the variant's state (worlds listed, port typed, maybe refused). */
    public static HostWorldScreen host(String variant) {
        HostWorldScreen s = new HostWorldScreen();
        List<String> worlds = worlds(variant);
        s.setWorldSource(() -> worlds);
        s.onShow();
        s.setPortText("25565");
        if (variant.contains("-status")) {
            s.startHosting("0"); // refused: Invalid port
            s.setPortText("0");
        }
        return s;
    }

    /** A join screen in the variant's state. */
    public static JoinWorldScreen join(String variant) {
        JoinWorldScreen s = new JoinWorldScreen();
        s.setFields("play.example.net", "25565", "Steve");
        if (variant.contains("-status")) {
            s.connect("play.example.net", "", "Steve"); // refused: Invalid port
        }
        return s;
    }

    static String hover(String variant) {
        int i = variant.indexOf("hover-");
        return i < 0 ? "" : variant.substring(i + "hover-".length());
    }

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
        switch (c.screen()) {
            case "host-world" -> services.hostWorld = host(c.variant());
            case "join-world" -> {
                services.joinWorld = join(c.variant());
                services.multiplayerBack = "stonebreak:screen.join-world.back";
            }
            default -> { }
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
            String hover = hover(c.variant());
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
