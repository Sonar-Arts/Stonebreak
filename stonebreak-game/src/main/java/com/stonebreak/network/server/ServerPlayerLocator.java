package com.stonebreak.network.server;

import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import org.joml.Vector3f;

import java.util.Collection;

/**
 * The connected roster as mob AI sees it: every player standing in the authoritative world, the
 * host included and no different from anyone else.
 *
 * <p>Installed on the headless world by {@link IntegratedServer#start}, which keeps the
 * {@code mobs} package free of network imports — the dependency flows network → mobs, as it does
 * for the spawner's anchor source. Read on the server tick thread, the same thread that writes
 * {@link ServerPlayer}'s reported transform, so no snapshot is needed.
 *
 * <p>Only players {@linkplain ServerPlayer#isLiveInWorld() live in the world} count: one still
 * handshaking sits at the {@code (0,0,0)} default, and a corpse behind the death menu should not
 * keep a herd running.
 */
public final class ServerPlayerLocator implements PlayerLocator {

    private final Collection<ServerPlayer> roster;

    /** @param roster a live view of the connected players ({@link ServerWorldContext#players()}) */
    public ServerPlayerLocator(Collection<ServerPlayer> roster) {
        this.roster = roster;
    }

    @Override
    public Vector3f nearestPlayer(Vector3f from, Vector3f out) {
        ServerPlayer nearest = nearest(from);
        return nearest == null ? null : out.set(nearest.x(), nearest.y(), nearest.z());
    }

    @Override
    public boolean nearestPlayerSprinting(Vector3f from) {
        ServerPlayer nearest = nearest(from);
        return nearest != null && nearest.isSprinting();
    }

    private ServerPlayer nearest(Vector3f from) {
        ServerPlayer best = null;
        float bestDistanceSq = Float.MAX_VALUE;
        for (ServerPlayer player : roster) {
            if (!player.isLiveInWorld()) {
                continue;
            }
            float dx = player.x() - from.x;
            float dy = player.y() - from.y;
            float dz = player.z() - from.z;
            float distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = player;
            }
        }
        return best;
    }
}
