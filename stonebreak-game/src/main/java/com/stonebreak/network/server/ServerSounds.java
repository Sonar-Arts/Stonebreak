package com.stonebreak.network.server;

import com.stonebreak.network.packet.world.SoundEventS2C;

/**
 * Sends sound events to the players close enough to hear them (issue #344). The server never
 * plays a sound itself: it names the subject and event ({@link SoundEventS2C}), and each nearby
 * client plays the sample its own copy of the asset binds.
 */
public final class ServerSounds {

    /**
     * How far a replicated sound carries. Past this the 3D mixer has attenuated it to almost
     * nothing, so sending it would only spend bandwidth.
     */
    public static final float HEARING_RANGE = 48f;

    private ServerSounds() {
    }

    /**
     * Sends {@code sound} to every in-world player within {@link #HEARING_RANGE} of its position,
     * except {@code except} (the player whose own client already played it; null for none).
     * Players that have not reported a position yet hear nothing — there is nowhere to measure
     * from.
     */
    public static void sendNear(ServerWorldContext ctx, SoundEventS2C sound, ServerPlayer except) {
        for (ServerPlayer sp : ctx.players()) {
            if (sp == except || !sp.handshakeDone() || sp.lastStateNs() == 0L) {
                continue;
            }
            if (inRange(sp.x(), sp.y(), sp.z(), sound.x(), sound.y(), sound.z())) {
                sp.send(sound, true);
            }
        }
    }

    /** Whether a listener at {@code (lx, ly, lz)} hears a sound at {@code (x, y, z)}. */
    static boolean inRange(float lx, float ly, float lz, float x, float y, float z) {
        float dx = x - lx;
        float dy = y - ly;
        float dz = z - lz;
        return dx * dx + dy * dy + dz * dz <= HEARING_RANGE * HEARING_RANGE;
    }
}
