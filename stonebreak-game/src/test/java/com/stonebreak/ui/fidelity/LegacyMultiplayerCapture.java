package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.multiplayerMenu.HostWorldScreen;
import com.stonebreak.ui.multiplayerMenu.JoinWorldScreen;
import com.stonebreak.ui.multiplayerMenu.MultiplayerMenu;
import com.stonebreak.ui.multiplayerMenu.MultiplayerUIPainter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The legacy side of the multiplayer screens' fidelity gates (#299), by the case's screen:
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
 * Parts are named as the documents name them; actions are the host actions the legacy clicks map to.
 * Not a test class.
 */
public final class LegacyMultiplayerCapture implements MigrationGate.Renderer {

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

    /** A host screen in the variant's state (worlds listed, port typed, maybe refused or focused). */
    public static HostWorldScreen host(SkijaUIBackend backend, String variant) {
        HostWorldScreen s = new HostWorldScreen(backend);
        List<String> worlds = worlds(variant);
        s.setWorldSource(() -> worlds);
        s.onShow();
        s.setPortText("25565");
        if (variant.contains("-status")) {
            s.startHosting("0"); // refused: Invalid port
            s.setPortText("0");
        }
        if (variant.contains("-focused")) {
            s.setPortFocused(true);
        }
        return s;
    }

    /** A join screen in the variant's state. */
    public static JoinWorldScreen join(SkijaUIBackend backend, String variant) {
        JoinWorldScreen s = new JoinWorldScreen(backend);
        s.setFields("play.example.net", "25565", "Steve", -1);
        if (variant.contains("-status")) {
            s.connect("play.example.net", "", "Steve"); // refused: Invalid port
        }
        if (variant.contains("-focused")) {
            s.setFields(s.hostText(), s.portText(), s.userText(), 0);
        }
        return s;
    }

    static String hover(String variant) {
        int i = variant.indexOf("hover-");
        return i < 0 ? "" : variant.substring(i + "hover-".length());
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            return switch (c.screen()) {
                case "multiplayer" -> menu(raster, c.variant(), w, h);
                case "host-world" -> host(raster, c.variant(), w, h);
                case "join-world" -> join(raster, c.variant(), w, h);
                default -> throw new IllegalArgumentException("not a multiplayer screen: " + c.screen());
            };
        }
    }

    private static Map<String, float[]> rects(MultiplayerUIPainter painter, Runnable render, Map<String, String> names) {
        Map<String, float[]> raw = new LinkedHashMap<>();
        painter.setLayoutSink(raw::put);
        render.run();
        painter.setLayoutSink(null);
        Map<String, float[]> out = new LinkedHashMap<>();
        names.forEach((key, name) -> {
            if (raw.containsKey(key)) {
                out.put(name, raw.get(key));
            }
        });
        return out;
    }

    private static float[] centre(float[] r) {
        return new float[]{r[0] + r[2] / 2f, r[1] + r[3] / 2f};
    }

    // ── menu ─────────────────────────────────────────────────────────────────

    static final Map<String, String> MENU_PARTS = Map.of("button:Host World", "host", "button:Join World", "join",
        "button:Back", "back");

    private static MigrationGate.Capture menu(LegacyUiRaster raster, String variant, int w, int h) {
        MultiplayerMenu menu = new MultiplayerMenu(raster.backend());
        try {
            Map<String, float[]> parts = rects(menu.painter(), () -> menu.render(w, h), MENU_PARTS);
            String hover = hover(variant);
            if (!hover.isEmpty()) {
                float[] p = centre(parts.get(hover));
                menu.handleMouseMove(p[0], p[1], w, h);
                menu.render(w, h);
            }
            raster.reset();
            menu.render(w, h);
            List<String> order = List.of("host", "join", "back");
            Map<String, float[]> hits = new LinkedHashMap<>();
            parts.forEach((name, r) -> hits.put(name, LegacyDeathCapture.probeRegion((x, y) -> {
                menu.handleMouseMove(x, y, w, h);
                return menu.hoveredButton() == order.indexOf(name);
            }, r, w, h)));
            Map<String, String> actions = new LinkedHashMap<>();
            parts.keySet().forEach(n -> actions.put(n, "stonebreak:screen.multiplayer." + n));
            return new MigrationGate.Capture(raster.capture(), parts, hits, actions);
        } finally {
            menu.dispose();
        }
    }

    // ── host ─────────────────────────────────────────────────────────────────

    private static Map<String, String> hostParts(List<String> worlds) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(worlds.size(), 8); i++) {
            m.put("button:" + worlds.get(i), "row" + i);
        }
        m.put("field:0", "port");
        m.put("button:Start Hosting", "start");
        m.put("button:Back", "back");
        return m;
    }

    private static MigrationGate.Capture host(LegacyUiRaster raster, String variant, int w, int h) {
        HostWorldScreen s = host(raster.backend(), variant);
        try {
            Map<String, float[]> parts = rects(s.painter(), () -> s.render(w, h), hostParts(worlds(variant)));
            String hover = hover(variant);
            if (!hover.isEmpty()) {
                float[] p = centre(parts.get(hover));
                s.handleMouseMove(p[0], p[1], w, h);
            }
            raster.reset();
            s.render(w, h);
            Map<String, float[]> hits = new LinkedHashMap<>();
            Map<String, String> actions = new LinkedHashMap<>();
            parts.forEach((name, r) -> {
                if (name.equals("port")) {
                    return; // focus, not an action: the field is compared as drawn
                }
                hits.put(name, LegacyDeathCapture.probeRegion((x, y) -> {
                    s.handleMouseMove(x, y, w, h);
                    return name.startsWith("row") ? s.hoveredWorld() == name.charAt(3) - '0'
                        : s.hoveredButton() == (name.equals("start") ? 0 : 1);
                }, r, w, h));
                actions.put(name, switch (name) {
                    case "start" -> "stonebreak:screen.host-world.start";
                    case "back" -> "stonebreak:screen.host-world.back";
                    default -> "stonebreak:screen.host-world.select " + name.substring(3);
                });
            });
            return new MigrationGate.Capture(raster.capture(), parts, hits, actions);
        } finally {
            s.dispose();
        }
    }

    // ── join ─────────────────────────────────────────────────────────────────

    static final Map<String, String> JOIN_PARTS = Map.of("field:0", "host", "field:1", "port", "field:2", "username",
        "button:Connect", "connect", "button:Back", "back");

    private static MigrationGate.Capture join(LegacyUiRaster raster, String variant, int w, int h) {
        JoinWorldScreen s = join(raster.backend(), variant);
        try {
            Map<String, float[]> parts = rects(s.painter(), () -> s.render(w, h), JOIN_PARTS);
            String hover = hover(variant);
            if (!hover.isEmpty()) {
                float[] p = centre(parts.get(hover));
                s.handleMouseMove(p[0], p[1], w, h);
            }
            raster.reset();
            s.render(w, h);
            Map<String, float[]> hits = new LinkedHashMap<>();
            Map<String, String> actions = new LinkedHashMap<>();
            for (String name : List.of("connect", "back")) {
                float[] r = parts.get(name);
                hits.put(name, LegacyDeathCapture.probeRegion((x, y) -> {
                    s.handleMouseMove(x, y, w, h);
                    return s.hoveredButton() == (name.equals("connect") ? 0 : 1);
                }, r, w, h));
                actions.put(name, "stonebreak:screen.join-world." + name);
            }
            return new MigrationGate.Capture(raster.capture(), parts, hits, actions);
        } finally {
            s.dispose();
        }
    }
}
