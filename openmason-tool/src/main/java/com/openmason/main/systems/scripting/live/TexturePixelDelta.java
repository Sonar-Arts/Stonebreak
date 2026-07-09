package com.openmason.main.systems.scripting.live;

/**
 * Full-texture before/after pixel state for one GPU texture touched by a
 * script run — the pixel companion to the part/mesh snapshots inside
 * {@link ScriptRunCommand} (mesh snapshots track mappings and materials but
 * not texel content).
 */
public record TexturePixelDelta(int gpuTextureId, int width, int height,
                                byte[] before, byte[] after) {
}
