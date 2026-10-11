package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.ui.LegacyUiClock;
import com.stonebreak.ui.terrainMapper.TerrainMapperScreen;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager.ActiveField;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The terrain mapper ({@code stonebreak:screen.terrain-mapper} 1, #301). {@link TerrainMapperScreen} stays the
 * controller (the keyboard: typing, Tab, Enter creates, Esc goes back, Ctrl+V; the seed's live preview; world
 * creation); the map itself is the {@code stonebreak:terrain-map} provider. Root {@code terrainMapper}: the two
 * fields ({@code name}/{@code seed} as typed, {@code nameDisplay}/{@code seedDisplay} with the placeholder,
 * {@code nameEmpty}/{@code seedEmpty}, {@code field} = name/seed/none, {@code caretOn}: the legacy 2 Hz blink,
 * {@code nameFocused}/{@code seedFocused}, {@code nameCaret}/{@code seedCaret}: the blink in that field),
 * {@code modes} ({@code mode}, {@code label}, {@code selected}), the spawn buttons ({@code spawnSet},
 * {@code spawnLabel}), {@code error}, the footer readout ({@code modeLabel}, {@code hover}).
 *
 * <p>Actions (window pixels, the screen's own coordinates): {@code .button {name}} (back, character, create,
 * simulate, spawn, center, mode:KIND), {@code .field {field}}, {@code .map-press {x, y}}, {@code .release {x, y}},
 * {@code .move {x, y}}, {@code .wheel {x, y, dy}}; each refuses unless the mapper is showing.
 */
public final class TerrainMapperContracts {

    public static final HostContract MAPPER = HostContract.of("stonebreak:screen.terrain-mapper", 1);

    public static final DataType.Obj STATE = DataType.object(
        "name", DataType.string(), "nameDisplay", DataType.string(), "nameEmpty", DataType.bool(),
        "seed", DataType.string(), "seedDisplay", DataType.string(), "seedEmpty", DataType.bool(),
        "field", DataType.string(), "caretOn", DataType.bool(), "nameFocused", DataType.bool(),
        "seedFocused", DataType.bool(), "nameCaret", DataType.bool(), "seedCaret", DataType.bool(),
        "modes", DataType.list(DataType.object("mode", DataType.string(), "label", DataType.string(),
            "selected", DataType.bool()), "mode"),
        "spawnSet", DataType.bool(), "spawnLabel", DataType.string(), "error", DataType.string(),
        "modeLabel", DataType.string(), "hover", DataType.string());

    private static final DataType.Obj POINT = DataType.object("x", DataType.number(), "y", DataType.number());

    /** The game behind the mapper. */
    public interface Services {
        /** The terrain mapper while it is the current screen, or null. */
        default TerrainMapperScreen terrainMapper() {
            return null;
        }

        /** The window in device px ({@code {w, h}}); null: unknown. */
        default int[] screenSize() {
            return null;
        }
    }

    private final Services services;
    private final DataCell state;
    private UiValue last;

    public TerrainMapperContracts(UiHost ui, Services services) {
        this.services = services;
        state = ui.data().register("terrainMapper", new DataCell(STATE, state(null)), MAPPER);
        action(ui, "button", DataType.object("name", DataType.string()), (t, a, w, h) ->
            t.mouse().pressButton(str(a, "name")) ? null : "no such control");
        action(ui, "field", DataType.object("field", DataType.string()), (t, a, w, h) -> {
            switch (str(a, "field")) {
                case "name" -> t.mouse().pressField(ActiveField.WORLD_NAME);
                case "seed" -> t.mouse().pressField(ActiveField.SEED);
                default -> {
                    return "no such field";
                }
            }
            return null;
        });
        action(ui, "map-press", POINT, (t, a, w, h) -> {
            t.mouse().pressMap((float) num(a, "x"), (float) num(a, "y"));
            return null;
        });
        action(ui, "release", POINT, (t, a, w, h) -> {
            t.mouse().release((float) num(a, "x"), (float) num(a, "y"), w, h);
            return null;
        });
        action(ui, "move", POINT, (t, a, w, h) -> {
            t.mouse().move((float) num(a, "x"), (float) num(a, "y"), w, h);
            return null;
        });
        action(ui, "wheel", DataType.object("x", DataType.number(), "y", DataType.number(), "dy", DataType.number()),
            (t, a, w, h) -> {
                t.mouse().wheel(num(a, "x"), num(a, "y"), num(a, "dy"), w, h);
                return null;
            });
    }

    /** UI thread, once per frame: the caret blinks and the readout follows the pointer. */
    public void poll() {
        TerrainMapperScreen t = services.terrainMapper();
        UiValue s = state(t == null ? null : t.getStateManager());
        if (!s.equals(last)) {
            last = s;
            state.set(s);
        }
    }

    static UiValue state(TerrainMapperStateManager s) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        String name = s == null ? "" : s.getWorldName();
        String seed = s == null ? "" : s.getSeedText();
        ActiveField field = s == null ? ActiveField.NONE : s.getActiveField();
        m.put("name", UiValue.of(name));
        m.put("nameDisplay", UiValue.of(name.isEmpty() ? "Enter a name..." : name));
        m.put("nameEmpty", UiValue.of(name.isEmpty()));
        m.put("seed", UiValue.of(seed));
        m.put("seedDisplay", UiValue.of(seed.isEmpty() ? "Random if blank" : seed));
        m.put("seedEmpty", UiValue.of(seed.isEmpty()));
        m.put("field", UiValue.of(switch (field) {
            case WORLD_NAME -> "name";
            case SEED -> "seed";
            case NONE -> "none";
        }));
        // TerrainSidebarRenderer's caret: 500 ms on the legacy UI clock, in the focused field
        boolean caret = (LegacyUiClock.millis() / 500L) % 2L == 0L;
        m.put("caretOn", UiValue.of(caret));
        m.put("nameFocused", UiValue.of(field == ActiveField.WORLD_NAME));
        m.put("seedFocused", UiValue.of(field == ActiveField.SEED));
        m.put("nameCaret", UiValue.of(caret && field == ActiveField.WORLD_NAME));
        m.put("seedCaret", UiValue.of(caret && field == ActiveField.SEED));
        VisualizerKind active = s == null ? VisualizerKind.HEIGHT : s.getActiveVisualizer();
        List<UiValue> modes = new ArrayList<>();
        for (VisualizerKind k : VisualizerKind.values()) {
            modes.add(new UiValue.Obj(Map.of("mode", UiValue.of(k.name()), "label", UiValue.of(k.displayName()),
                "selected", UiValue.of(k == active))));
        }
        m.put("modes", new UiValue.Arr(modes));
        boolean spawn = s != null && s.hasSpawnPoint();
        m.put("spawnSet", UiValue.of(spawn));
        m.put("spawnLabel", UiValue.of(spawn ? "Random Spawn?" : "Click map to set spawn"));
        String error = s == null ? null : s.getErrorMessage();
        m.put("error", UiValue.of(error == null ? "" : error));
        m.put("modeLabel", UiValue.of("Mode: " + active.displayName()));
        String hover = "";
        if (s != null && s.hasHoverValue()) {
            NoiseVisualizer v = s.getVisualizers().get(active);
            String formatted = v != null ? v.formatValue(s.hoverRawValue()) : String.format("%.3f", s.hoverRawValue());
            hover = String.format("(%d, %d) = %s", s.hoverWorldX(), s.hoverWorldZ(), formatted);
        }
        m.put("hover", UiValue.of(hover));
        return new UiValue.Obj(m);
    }

    @FunctionalInterface
    private interface Rule {
        String apply(TerrainMapperScreen screen, UiValue.Obj args, int width, int height);
    }

    private void action(UiHost ui, String name, DataType.Obj params, Rule rule) {
        ui.actions().register(ActionSpec.of(MAPPER.id() + "." + name, MAPPER, params, DataType.ANY), (args, ctx) -> {
            TerrainMapperScreen t = services.terrainMapper();
            int[] size = services.screenSize();
            String problem = t == null ? "the terrain mapper is not showing"
                : rule.apply(t, args instanceof UiValue.Obj o ? o : new UiValue.Obj(Map.of()),
                    size == null ? 0 : size[0], size == null ? 0 : size[1]);
            poll();
            return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
                : CompletableFuture.failedFuture(new IllegalStateException(problem));
        });
    }

    private static String str(UiValue.Obj a, String k) {
        return a.get(k) instanceof UiValue.Str s ? s.value() : "";
    }

    private static double num(UiValue.Obj a, String k) {
        return a.get(k) instanceof UiValue.Num n ? n.value() : 0;
    }
}
