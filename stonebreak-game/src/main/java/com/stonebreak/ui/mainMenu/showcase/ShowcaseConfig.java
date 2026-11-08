package com.stonebreak.ui.mainMenu.showcase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.util.easing.EasingType;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The main menu showcase: the pinned seed and the drone shots flown over it, read from
 * {@value #RESOURCE}. Data, not code: retuning a shot is a JSON edit.
 *
 * <pre>{@code
 * { "seed": 123, "shots": [ {
 *     "caption": "Stony Peaks", "motion": "ORBIT",
 *     "eyeFrom": [x,y,z], "eyeTo": [x,y,z], "targetFrom": [x,y,z], "targetTo": [x,y,z],
 *     "sweepDeg": 35, "fov": [60, 55], "roll": [0, 0], "duration": 12,
 *     "easing": "EaseInOutQuad", "time": 11500, "weather": "CLEAR", "loop": false } ] }
 * }</pre>
 * {@code eyeTo}/{@code targetTo} default to their {@code From}; {@code fov} and {@code roll} take one
 * number or a {@code [from, to]} pair; {@code time} (absolute day ticks) and {@code weather} are
 * optional and applied while the screen is black before the shot. {@code loop: true} makes the shot run
 * forever with no cut ({@link FlyoverShot#loop}).
 */
public record ShowcaseConfig(long seed, List<FlyoverShot> shots) {

    public static final String RESOURCE = "/menu/showcase.json";

    public ShowcaseConfig {
        shots = List.copyOf(shots);
        if (shots.isEmpty()) {
            throw new IllegalArgumentException("the showcase needs at least one shot");
        }
    }

    /** The shipped showcase ({@value #RESOURCE}). */
    public static ShowcaseConfig load() throws IOException {
        try (InputStream in = ShowcaseConfig.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException(RESOURCE + " is missing");
            }
            return parse(in);
        }
    }

    public static ShowcaseConfig parse(InputStream in) throws IOException {
        JsonNode root = new ObjectMapper().readTree(in);
        long seed = root.path("seed").asLong();
        List<FlyoverShot> shots = new ArrayList<>();
        for (JsonNode s : root.path("shots")) {
            float[] fov = pair(s.get("fov"), 70f);
            float[] roll = pair(s.get("roll"), 0f);
            JsonNode sweep = s.get("sweepDeg");
            shots.add(new FlyoverShot(
                    s.path("caption").asText(""),
                    FlyoverShot.Motion.valueOf(s.path("motion").asText("LINEAR")),
                    vec(s.get("eyeFrom"), "eyeFrom"),
                    s.has("eyeTo") ? vec(s.get("eyeTo"), "eyeTo") : null,
                    vec(s.get("targetFrom"), "targetFrom"),
                    s.has("targetTo") ? vec(s.get("targetTo"), "targetTo") : null,
                    sweep == null ? Float.NaN : (float) sweep.asDouble(),
                    fov[0], fov[1], roll[0], roll[1],
                    (float) s.path("duration").asDouble(12.0),
                    EasingType.valueOf(s.path("easing").asText(EasingType.EaseInOutQuad.name())),
                    s.has("time") ? s.get("time").asLong() : null,
                    s.has("weather") ? s.get("weather").asText() : null,
                    s.path("loop").asBoolean(false)));
        }
        return new ShowcaseConfig(seed, shots);
    }

    private static Vector3f vec(JsonNode n, String name) throws IOException {
        if (n == null || !n.isArray() || n.size() != 3) {
            throw new IOException("showcase shot: '" + name + "' must be [x, y, z]");
        }
        return new Vector3f((float) n.get(0).asDouble(), (float) n.get(1).asDouble(), (float) n.get(2).asDouble());
    }

    private static float[] pair(JsonNode n, float fallback) {
        if (n == null) {
            return new float[]{fallback, fallback};
        }
        if (n.isArray()) {
            float a = (float) n.path(0).asDouble(fallback);
            return new float[]{a, (float) n.path(1).asDouble(a)};
        }
        float v = (float) n.asDouble(fallback);
        return new float[]{v, v};
    }
}
