package com.stonebreak.world.weather;

import java.util.ArrayList;
import java.util.List;

/**
 * A region of the world held at a fixed {@link WeatherMode} by {@code /weather}:
 * a disc fixed in world space (the weather there does not blow away), whose
 * edge fades over {@link #EDGE_FRACTION} of its radius into the weather around it.
 *
 * @param x      world centre
 * @param z      world centre
 * @param radius blocks to the outer edge of the fade
 * @param mode   the weather inside; never {@link WeatherMode#AUTO}
 */
public record WeatherZone(double x, double z, float radius, WeatherMode mode) {

    /** Radius a zone placed by {@code /weather <mode>} gets, blocks. */
    public static final float DEFAULT_RADIUS = 1500f;
    /** Share of the radius the zone fades out over. */
    static final float EDGE_FRACTION = 1f / 3f;

    /** How much of this zone's weather a world column sees: 1 inside, 0 outside, smooth between. */
    public float weight(double worldX, double worldZ) {
        double dx = worldX - x, dz = worldZ - z;
        float d = (float) Math.sqrt(dx * dx + dz * dz);
        return 1f - CloudField.smoothstep(radius * (1f - EDGE_FRACTION), radius, d);
    }

    /** Zones as one save-file string, {@code "x,z,radius,MODE;..."}; see {@link #parseAll}. */
    public static String formatAll(List<WeatherZone> zones) {
        StringBuilder sb = new StringBuilder();
        for (WeatherZone z : zones) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(z.x).append(',').append(z.z).append(',').append(z.radius).append(',').append(z.mode.name());
        }
        return sb.toString();
    }

    /** Reads {@link #formatAll}'s string; null, blank and malformed entries give no zone. */
    public static List<WeatherZone> parseAll(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<WeatherZone> out = new ArrayList<>();
        for (String entry : text.split(";")) {
            String[] f = entry.split(",");
            if (f.length != 4) continue;
            try {
                WeatherMode mode = WeatherMode.parse(f[3]);
                if (mode == null || mode == WeatherMode.AUTO) continue;
                out.add(new WeatherZone(Double.parseDouble(f[0]), Double.parseDouble(f[1]),
                        Float.parseFloat(f[2]), mode));
            } catch (NumberFormatException ignored) {
                // a hand-edited entry; drop it
            }
        }
        return List.copyOf(out);
    }

    /** Whether ({@code worldX}, {@code worldZ}) feels this zone at all. */
    public boolean covers(double worldX, double worldZ) {
        return weight(worldX, worldZ) > 0f;
    }
}
