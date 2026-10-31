package com.stonebreak.network.server;

import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.network.packet.player.PlayerHealthS2C;
import com.stonebreak.network.server.handlers.ServerHealthHandler;
import com.stonebreak.player.interaction.RaycastEngine;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.function.Consumer;

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
 * <p>Mobs hurt players through it too (issue #343): a melee blow lands on the player it targets and
 * a mob's arrow on the player it is inside, both through the server-owned health
 * ({@link ServerHealthHandler}). Built without one (tests), hits land on nobody.
 */
public final class ServerPlayerLocator implements PlayerLocator {

    /** A blow never reaches a player further than this, whatever the behaviour asked for. */
    static final float MAX_MELEE_DISTANCE = 6f;

    private final Collection<ServerPlayer> roster;
    /** Whose world sight is marched through; null (tests) sees through everything. */
    private final ServerWorldContext ctx;
    private final ServerHealthHandler health;

    private RaycastEngine sightRay;
    /** The world {@link #sightRay} was built over. */
    private World sightWorld;

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
    public float nearestPlayerLoudness(Vector3f from) {
        ServerPlayer nearest = nearest(from);
        return nearest != null ? nearest.movementLoudness() : 0f;
    }

    @Override
    public boolean nearestPlayerStealthed(Vector3f from) {
        ServerPlayer nearest = nearest(from);
        return nearest != null && nearest.isStealthed();
    }

    /** Nothing solid between {@code eye} and the nearest player's chest. */
    @Override
    public boolean canSeeNearestPlayer(Vector3f from, Vector3f eye) {
        return canSee(nearest(from), eye, false);
    }

    @Override
    public void forEachPlayer(Vector3f from, float radius, Consumer<SensedPlayer> sink) {
        float radiusSq = radius * radius;
        for (ServerPlayer player : roster) {
            if (!player.isLiveInWorld()) {
                continue;
            }
            Vector3f position = new Vector3f(player.x(), player.y(), player.z());
            if (position.distanceSquared(from) <= radiusSq) {
                sink.accept(new SensedPlayer(player.playerId(), position, player.movementLoudness(),
                        player.isStealthed(), player.stealthSkill()));
            }
        }
    }

    @Override
    public Vector3f playerPosition(int id, Vector3f from, Vector3f out) {
        ServerPlayer player = byId(id, from);
        return player == null ? null : out.set(player.x(), player.y(), player.z());
    }

    @Override
    public boolean canSeePlayer(int id, Vector3f from, Vector3f eye) {
        return canSee(byId(id, from), eye, false);
    }

    @Override
    public boolean canSeePlayerThroughLeaves(int id, Vector3f from, Vector3f eye) {
        return canSee(byId(id, from), eye, true);
    }

    private boolean canSee(ServerPlayer player, Vector3f eye, boolean throughLeaves) {
        if (player == null) {
            return false;
        }
        World world = ctx != null ? ctx.world() : null;
        if (world == null) {
            return true;
        }
        Vector3f dir = new Vector3f(player.x(), player.y() + CHEST_HEIGHT, player.z()).sub(eye);
        float dist = dir.length();
        if (dist < 1.0e-4f) {
            return true;
        }
        if (sightRay == null || sightWorld != world) {
            sightRay = new RaycastEngine(null, null, world);
            sightWorld = world;
        }
        return sightRay.distanceToFirstSolid(eye, dir.div(dist), dist, throughLeaves) >= dist;
    }

    /** The nearest player takes the blow, shoved horizontally away from {@code from}. */
    @Override
    public boolean hurtNearestPlayer(Vector3f from, float amount, float knockback) {
        return hurt(nearest(from), from, amount, knockback);
    }

    @Override
    public boolean hurtPlayer(int id, Vector3f from, float amount, float knockback) {
        return hurt(byId(id, from), from, amount, knockback);
    }

    private boolean hurt(ServerPlayer target, Vector3f from, float amount, float knockback) {
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

    /** The camera convention: yaw about +Y from +X, pitch up from the horizon, in degrees. */
    @Override
    public Vector3f playerLook(int id, Vector3f from, Vector3f out) {
        ServerPlayer player = byId(id, from);
        if (player == null) {
            return null;
        }
        double yaw = Math.toRadians(player.yaw());
        double pitch = Math.toRadians(player.pitch());
        return out.set((float) (Math.cos(yaw) * Math.cos(pitch)), (float) Math.sin(pitch),
                (float) (Math.sin(yaw) * Math.cos(pitch))).normalize();
    }

    @Override
    public boolean ignitePlayerAt(Vector3f point, float margin, float seconds, float damagePerSecond) {
        for (ServerPlayer player : roster) {
            if (player.isLiveInWorld() && ServerHealthHandler.insideBody(player, point, margin)) {
                player.vitals().ignite(seconds, damagePerSecond);
                return true;
            }
        }
        return false;
    }

    private static float distanceSq(ServerPlayer player, Vector3f from) {
        float dx = player.x() - from.x;
        float dy = player.y() - from.y;
        float dz = player.z() - from.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Live player {@code id}; {@link #NEAREST} means the one nearest {@code from}. */
    private ServerPlayer byId(int id, Vector3f from) {
        if (id == NEAREST) {
            return nearest(from);
        }
        for (ServerPlayer player : roster) {
            if (player.playerId() == id && player.isLiveInWorld()) {
                return player;
            }
        }
        return null;
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
