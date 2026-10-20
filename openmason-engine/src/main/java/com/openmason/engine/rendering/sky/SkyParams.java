package com.openmason.engine.rendering.sky;

import org.joml.Vector3f;

/**
 * One frame's sky, as the caller's time-of-day system resolves it. Keeps
 * {@link SkyRenderer} free of any game-world coupling.
 *
 * @param sunDirection   normalized direction to the sun (world space)
 * @param moonDirection  normalized direction to the moon (world space)
 * @param moonLight      moonlight relative to a full moon overhead (0..1): drives the halo
 * @param zenithColor    sky colour straight up
 * @param horizonColor   sky colour at the horizon
 * @param sunGlow        warm light low on the horizon toward the sun, premultiplied by strength
 * @param daylight       0 with the sun down, 1 in full day: fades the haze in and washes the moon out
 */
public record SkyParams(Vector3f sunDirection, Vector3f moonDirection, float moonLight,
                        Vector3f zenithColor, Vector3f horizonColor, Vector3f sunGlow,
                        float daylight) {
}
