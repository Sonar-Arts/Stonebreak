package com.openmason.engine.ui.fidelity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A retired renderer's captures, frozen (#302): each case's frame stays a {@link GoldenStore}
 * baseline PNG and its rects, hit regions and actions go to one {@code geometry.json} beside them.
 * {@link #replay()} serves them back as the {@link MigrationGate} reference, so a screen keeps its
 * gate after the legacy code that drew the reference is deleted.
 *
 * <p>Floats are written with their shortest round-tripping form, so a replayed rect is bit-identical
 * to the recorded one and {@link GeometryRule#FLOAT_EXACT} still holds.
 */
public final class FrozenCaptures {

    public static final String GEOMETRY_FILE = "geometry.json";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final GoldenStore store;
    private final String screen;
    private Map<String, JsonNode> cases;

    public FrozenCaptures(GoldenStore store, String screen) {
        this.store = Objects.requireNonNull(store, "store");
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    /** The usual layout: {@code src/test/resources/ui/fidelity/<screen>/}. */
    public static FrozenCaptures forTests(String screen) {
        return new FrozenCaptures(GoldenStore.forTests("ui/fidelity/" + screen, "ui.fidelity.write"), screen);
    }

    /** The frozen reference: the baseline PNG plus the recorded geometry of each case. */
    public MigrationGate.Renderer replay() {
        return c -> {
            FidelityImage image = store.read(c.id());
            if (image == null) {
                throw new AssertionError("no frozen baseline " + store.baseline(c.id()));
            }
            JsonNode node = cases().get(c.id());
            if (node == null) {
                throw new AssertionError("no frozen geometry for " + c.id() + " in " + geometryFile());
            }
            return new MigrationGate.Capture(image, rects(node.get("rects")), rects(node.get("hits")),
                actions(node.get("actions")));
        };
    }

    /** Records {@code live} for every case: PNGs through the store, geometry into one file. */
    public void record(List<FidelityCase> matrix, MigrationGate.Renderer live) {
        ObjectNode root = JSON.createObjectNode();
        root.put("screen", screen);
        root.put("units", "framebuffer pixels; rect = [x, y, width, height], origin top-left");
        ObjectNode all = root.putObject("cases");
        for (FidelityCase c : matrix) {
            MigrationGate.Capture cap = live.render(c);
            cap.image().write(store.baseline(c.id()));
            ObjectNode node = all.putObject(c.id());
            writeRects(node.putObject("rects"), cap.rects());
            writeRects(node.putObject("hits"), cap.hits());
            ObjectNode actions = node.putObject("actions");
            new TreeMap<>(cap.actions()).forEach(actions::put);
        }
        try {
            Files.createDirectories(geometryFile().getParent());
            JSON.writeValue(geometryFile().toFile(), root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + geometryFile(), e);
        }
        cases = null;
    }

    public Path geometryFile() {
        return store.baseline("x").resolveSibling(GEOMETRY_FILE);
    }

    private Map<String, JsonNode> cases() {
        if (cases == null) {
            try {
                JsonNode root = JSON.readTree(geometryFile().toFile());
                Map<String, JsonNode> out = new LinkedHashMap<>();
                root.get("cases").properties().forEach(e -> out.put(e.getKey(), e.getValue()));
                cases = out;
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + geometryFile(), e);
            }
        }
        return cases;
    }

    private static void writeRects(ObjectNode into, Map<String, float[]> rects) {
        new TreeMap<>(rects).forEach((name, r) -> {
            ArrayNode a = into.putArray(name);
            for (float v : r) {
                a.add(v);
            }
        });
    }

    private static Map<String, float[]> rects(JsonNode node) {
        Map<String, float[]> out = new LinkedHashMap<>();
        if (node != null) {
            node.properties().forEach(e -> {
                float[] r = new float[e.getValue().size()];
                for (int i = 0; i < r.length; i++) {
                    r[i] = e.getValue().get(i).floatValue();
                }
                out.put(e.getKey(), r);
            });
        }
        return out;
    }

    private static Map<String, String> actions(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null) {
            node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }
}
