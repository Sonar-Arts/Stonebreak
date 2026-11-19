package com.stonebreak.world.generation.diffusion;

import java.util.Arrays;

/**
 * A tile's surface channels (TGMPipe v3): per {@link SurfaceChannel#slot() slot} a row-major byte plane of the
 * tile's columns (same layout as {@link TerrainTile}'s planes) or null when the tile does not carry that channel.
 * Bytes are unsigned; {@link SurfaceChannel#NO_DATA} = no data. Immutable after decoding.
 */
public final class SurfaceChannels {
    /** A tile without surface channels (older service, overview tile, model without the producing nodes). */
    public static final SurfaceChannels NONE = new SurfaceChannels(new byte[SurfaceChannel.SLOTS][]);

    private final byte[][] planes;   // [slot] -> plane or null

    public SurfaceChannels(byte[][] planesBySlot) {
        if (planesBySlot.length != SurfaceChannel.SLOTS) {
            throw new IllegalArgumentException("expected " + SurfaceChannel.SLOTS + " slots");
        }
        this.planes = planesBySlot;
    }

    public boolean has(SurfaceChannel channel) {
        return planes[channel.slot()] != null;
    }

    /** True when no channel is present. */
    public boolean isEmpty() {
        return Arrays.stream(planes).allMatch(p -> p == null);
    }

    /** Unsigned byte at a plane index, {@link SurfaceChannel#NO_DATA} when the channel is absent. */
    public int at(SurfaceChannel channel, int index) {
        byte[] p = planes[channel.slot()];
        return p == null ? SurfaceChannel.NO_DATA : p[index] & 0xFF;
    }

    /** The raw plane of a slot (null when absent); do not modify. */
    public byte[] plane(int slot) {
        return planes[slot];
    }
}
