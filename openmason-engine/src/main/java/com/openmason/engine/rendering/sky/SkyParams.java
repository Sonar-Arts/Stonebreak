package com.openmason.engine.rendering.sky;

import org.joml.Vector3f;

/**
 * One frame's sky, as the caller's time-of-day system resolves it. Keeps
 * {@link SkyRenderer} free of any game-world coupling.
 *
 * @param sunDirection   normalized direction to the sun (world space)
 * @param moonDirection  normalized direction to the moon (world space)
 * @param moonLight      moonlight relative to a full moon overhead (0..1): drives the halo
 * @param zenithColor    sky colour straight up, away from any sunset
 * @param horizonColor   sky colour at the horizon, away from any sunset
 * @param sunsetColor    sunset gold × its strength (black for none), drawn over the sky's sun side
 * @param sunsetCoverage how far round from the sun the gold reaches: 1.15 covers the sky,
 *                       about 85% on the opposite horizon; below 0 none (see {@code sky.frag})
 * @param sunGlow        the afterglow: red light low on the horizon toward the sun, premultiplied by strength
 * @param sunColor       sunlight's hue (brightest channel 1): the disc and its glow take it
 * @param earthShadowColor  the Earth's shadow, the band rising opposite the sun after sunset
 * @param earthShadowHeight its edge's height straight opposite the sun (sine of elevation)
 * @param twilightBand   0..1: how strongly the shadow and the pink Belt of Venus above it show
 * @param daylight       0 with the sun down, 1 in full day: fades the haze in and washes the moon out
 * @param seaHorizon     the sea's edge as the eye sees it (sine of elevation, 0 or below): the sun
 *                       shines fully until it reaches it, then fades as it sinks
 */
public record SkyParams(Vector3f sunDirection, Vector3f moonDirection, float moonLight,
                        Vector3f zenithColor, Vector3f horizonColor,
                        Vector3f sunsetColor, float sunsetCoverage, Vector3f sunGlow,
                        Vector3f sunColor, Vector3f earthShadowColor, float earthShadowHeight,
                        float twilightBand, float daylight, float seaHorizon) {
}
