package com.stonebreak.core;

import com.stonebreak.config.TerrainScaleOption;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;

/**
 * The {@code -Dstonebreak.autoworld} dev hook's value: {@code <name>[:<seed>][:scale=<1:4|1:2>]} (the scale
 * itself holds a colon, so it is taken off first), with {@code -Dstonebreak.autoscale=<1:4|1:2>} as the
 * alternative way to give the scale. A scale only applies when the world is created by the hook.
 *
 * @param scale     the requested terrain scale, or null for the default / an existing world's own
 * @param badSeed   a seed was given but did not parse (the default was used)
 */
record AutoWorldSpec(String name, long seed, TerrainScalePreset scale, boolean badSeed) {

    static final long DEFAULT_SEED = 20260820L;

    static AutoWorldSpec parse(String spec, String autoscale) {
        String rest = spec.trim();
        TerrainScalePreset scale = TerrainScaleOption.parse(autoscale);
        int at = rest.indexOf(":scale=");
        if (at >= 0) {
            TerrainScalePreset fromSpec = TerrainScaleOption.parse(rest.substring(at + ":scale=".length()));
            if (fromSpec != null) {
                scale = fromSpec;
            }
            rest = rest.substring(0, at);
        }
        String name = rest;
        long seed = DEFAULT_SEED;
        boolean bad = false;
        int colon = rest.lastIndexOf(':');
        if (colon > 0) {
            name = rest.substring(0, colon);
            try {
                seed = Long.parseLong(rest.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                bad = true;
            }
        }
        return new AutoWorldSpec(name, seed, scale, bad);
    }
}
