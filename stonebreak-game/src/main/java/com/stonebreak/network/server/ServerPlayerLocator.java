package com.stonebreak.network.server;

import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.network.packet.player.PlayerHealthS2C;
import com.stonebreak.network.server.handlers.ServerHealthHandler;
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
 *
 * <p>Mobs hurt players through it too (issue #343): a melee blow lands on the nearest player and a
 * mob's arrow on the player it is inside, both through the server-owned health
 * ({@link ServerHealthHandler}). Built without one (tests), hits land on nobody.
 */
public final class ServerPlayerLocator implements PlayerLocator {

    /** A blow never reaches a player further than this, whatever the behaviour asked for. */
    static final float MAX_MELEE_DISTANCE = 6f;

    private final Collection<ServerPlayer> roster;
    private final ServerWorldContext ctx;
    private final ServerHealthHandler health;

    /** @param roster a live view of the connected players ({@link ServerWorldContext#players()}) */
    public ServerPlayerLocator(Collection<ServerPlayer> roster) {
        this(roster, null, null);
    }

    /** A locator whose hits land on the server-owned health in {@code health}. */
    public ServerPlayerLocator(Collection<ServerPlayer> roster, ServerWorldContext ctx,
                               ServerHealthHandler health) {
        this.roster = roster;
        this.ctx = ctx;
        this.health = health;
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

    @Override
    public boolean hurtNearestPlayer(Vector3f from, float amount, float knockback) {
        ServerPlayer target = nearest(from);
        if (health == null || target == null
                || distanceSq(target, from) > MAX_MELEE_DISTANCE * MAX_MELEE_DISTANCE) {
            return false;
        }
        if (health.hurt(target, amount, PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx) > 0f) {
            health.knockback(target, from, knockback);
        }
        return true;
    }

    @Override
    public boolean hitPlayerAt(Vector3f point, float margin, float amount, int ownerPlayerId) {
        if (health == null) {
            return false;
        }
        for (ServerPlayer player : roster) {
            if (!player.isLiveInWorld() || player.playerId() == ownerPlayerId
                    || !ServerHealthHandler.insideBody(player, point, margin)) {
                continue;
            }
            if (ownerPlayerId >= 0) {
                return health.hitByPlayerProjectile(ctx.player(ownerPlayerId), player, amount, ctx);
            }
            health.hurt(player, amount, PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx);
            return true;
        }
        return false;
    }

    private static float distanceSq(ServerPlayer player, Vector3f from) {
        float dx = player.x() - from.x;
        float dy = player.y() - from.y;
        float dz = player.z() - from.z;
        return dx * dx + dy * dy + dz * dz;
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
