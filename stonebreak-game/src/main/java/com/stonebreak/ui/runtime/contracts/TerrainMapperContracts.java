package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.masonry.MCategoryButton;
import com.stonebreak.ui.UiWallClock;
import com.stonebreak.ui.terrainMapper.TerrainMapperLayout;
import com.stonebreak.ui.terrainMapper.TerrainMapperScreen;
import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager.ActiveField;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;
import com.stonebreak.world.generation.TerrainGeneratorType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The terrain mapper ({@code stonebreak:screen.terrain-mapper} 2: #301, v2 the Daedalus generator choice). {@link TerrainMapperScreen} stays the
 * controller (the keyboard: typing, Tab, Enter creates, Esc goes back, Ctrl+V; the seed's live preview; world
 * creation); the map itself is the {@code stonebreak:terrain-map} provider. Root {@code terrainMapper}: the two
 * fields ({@code name}/{@code seed} as typed, {@code nameDisplay}/{@code seedDisplay} with the placeholder,
 * {@code nameEmpty}/{@code seedEmpty}, {@code field} = name/seed/none, {@code caretOn}: the 2 Hz blink,
 * {@code nameFocused}/{@code seedFocused}, {@code nameCaret}/{@code seedCaret}: the blink in that field),
 * {@code generators} ({@code gen}, {@code label}, {@code selected}, {@code warn}: the experimental model's hover
 * warning, its button's {@code y}), the chosen generator's {@code modes} ({@code mode}, {@code label},
 * {@code selected}, {@code x}/{@code y}/{@code w}: a two-column grid; none before a generator is picked) under
 * {@code hasModes}, the sections' tops ({@code genCaption}, {@code modeCaption}, {@code spawnCaption},
 * {@code spawnY}, {@code centerY}: window pixels from {@link TerrainMapperLayout}, which moves the spawn section
 * with the mode rows), the spawn buttons ({@code spawnSet}, {@code spawnLabel}), {@code createEnabled} (Create
 * waits for a generator), {@code warning}, {@code error}, the footer readout ({@code modeLabel}: mode and terrain,
 * {@code hover}).
 *
 * <p>{@code .button {name}} also takes {@code generator:TYPE}.
 *
 * <p>Actions (window pixels, the screen's own coordinates): {@code .button {name}} (back, character, create,
 * simulate, spawn, center, mode:KIND), {@code .field {field}}, {@code .map-press {x, y}}, {@code .release {x, y}},
 * {@code .move {x, y}}, {@code .wheel {x, y, dy}}; each refuses unless the mapper is showing.
 */
public final class TerrainMapperContracts {

    public static final HostContract MAPPER = HostContract.of("stonebreak:screen.terrain-mapper", 2);

    public static final DataType.Obj STATE = DataType.object(
        "name", DataType.string(), "nameDisplay", DataType.string(), "nameEmpty", DataType.bool(),
        "seed", DataType.string(), "seedDisplay", DataType.string(), "seedEmpty", DataType.bool(),
        "field", DataType.string(), "caretOn", DataType.bool(), "nameFocused", DataType.bool(),
        "seedFocused", DataType.bool(), "nameCaret", DataType.bool(), "seedCaret", DataType.bool(),
        "generators", DataType.list(DataType.object("gen", DataType.string(), "label", DataType.string(),
            "selected", DataType.bool(), "warn", DataType.bool(), "y", DataType.number()), "gen"),
        "modes", DataType.list(DataType.object("mode", DataType.string(), "label", DataType.string(),
            "selected", DataType.bool(), "x", DataType.number(), "y", DataType.number(), "w", DataType.number()),
            "mode"),
        "hasModes", DataType.bool(), "genCaption", DataType.number(), "modeCaption", DataType.number(),
        "spawnCaption", DataType.number(), "spawnY", DataType.number(), "centerY", DataType.number(),
        "spawnSet", DataType.bool(), "spawnLabel", DataType.string(), "createEnabled", DataType.bool(),
        "createDisabled", DataType.bool(),
        "warning", DataType.string(), "error", DataType.string(), "modeLabel", DataType.string(),
        "hover", DataType.string());

    /** A caption's top: its baseline sits 6 px above the control it labels, 18 px into its 18 px box. */
    private static final float CAPTION_ABOVE = 6f + 18f;

    /** The experimental generator's footer name: the backend and model, fixed at launch. */
    private static final String MODEL_LABEL =
        com.stonebreak.world.generation.diffusion.tgmpipe.TGMPipe.generatorLabel();

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
        // the caret blinks every 500 ms on the UI clock, in the focused field
        boolean caret = (UiWallClock.millis() / 500L) % 2L == 0L;
        m.put("caretOn", UiValue.of(caret));
        m.put("nameFocused", UiValue.of(field == ActiveField.WORLD_NAME));
        m.put("seedFocused", UiValue.of(field == ActiveField.SEED));
        m.put("nameCaret", UiValue.of(caret && field == ActiveField.WORLD_NAME));
        m.put("seedCaret", UiValue.of(caret && field == ActiveField.SEED));
        VisualizerKind active = s == null ? null : s.getActiveVisualizer();
        TerrainGeneratorType generator = s == null ? null : s.getSelectedGenerator();
        int modeCount = s == null ? 0 : s.getModeButtons().size();
        // the window size never moves the sidebar: only the mode rows push the spawn section down
        TerrainMapperLayout l = new TerrainMapperLayout(1920, 1080, modeCount);
        List<UiValue> generators = new ArrayList<>();
        TerrainGeneratorType[] types = TerrainGeneratorType.values();
        for (int i = 0; i < types.length; i++) {
            generators.add(new UiValue.Obj(Map.of("gen", UiValue.of(types[i].name()),
                "label", UiValue.of(types[i].displayName()), "selected", UiValue.of(types[i] == generator),
                "warn", UiValue.of(types[i] == TerrainGeneratorType.DIFFUSION),
                "y", UiValue.of(l.generatorButtonY(i)))));
        }
        m.put("generators", new UiValue.Arr(generators));
        List<UiValue> modes = new ArrayList<>();
        if (s != null) {
            float w = (TerrainMapperConfig.SIDEBAR_WIDTH - TerrainMapperConfig.SIDEBAR_PADDING * 2f
                - TerrainMapperConfig.MODE_BUTTON_SPACING) / TerrainMapperConfig.MODE_BUTTON_COLUMNS;
            int i = 0;
            for (MCategoryButton<VisualizerKind> b : s.getModeButtons()) {
                VisualizerKind k = b.tag();
                modes.add(new UiValue.Obj(Map.of("mode", UiValue.of(k.name()), "label", UiValue.of(k.displayName()),
                    "selected", UiValue.of(k == active), "x", UiValue.of(l.modeButtonX(i)),
                    "y", UiValue.of(l.modeButtonY(i)), "w", UiValue.of(w))));
                i++;
            }
        }
        m.put("modes", new UiValue.Arr(modes));
        m.put("hasModes", UiValue.of(!modes.isEmpty()));
        m.put("genCaption", UiValue.of(l.firstGeneratorButton().y() - CAPTION_ABOVE));
        m.put("modeCaption", UiValue.of(l.firstModeButton().y() - CAPTION_ABOVE));
        m.put("spawnCaption", UiValue.of(l.spawnButton().y() - CAPTION_ABOVE));
        m.put("spawnY", UiValue.of(l.spawnButton().y()));
        m.put("centerY", UiValue.of(l.centerOnSpawnButton().y()));
        boolean spawn = s != null && s.hasSpawnPoint();
        m.put("spawnSet", UiValue.of(spawn));
        m.put("spawnLabel", UiValue.of(spawn ? "Random Spawn?" : "Click map to set spawn"));
        boolean create = s != null && s.getCreateButton().enabled();
        m.put("createEnabled", UiValue.of(create));
        m.put("createDisabled", UiValue.of(!create));
        m.put("warning", UiValue.of(TerrainMapperConfig.DIFFUSION_WARNING));
        String error = s == null ? null : s.getErrorMessage();
        m.put("error", UiValue.of(error == null ? "" : error));
        String terrain = generator == null ? "none"
            : generator == TerrainGeneratorType.DIFFUSION ? MODEL_LABEL : generator.displayName();
        m.put("modeLabel", UiValue.of((active == null ? "Mode: none" : "Mode: " + active.displayName())
            + "   \u00b7   Terrain: " + terrain));
        String hover = "";
        if (s != null && s.hasHoverValue() && active != null) {
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
