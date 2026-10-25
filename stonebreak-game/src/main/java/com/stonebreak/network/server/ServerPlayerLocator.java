package com.stonebreak.network.server;

import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.network.packet.player.PlayerHurtS2C;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.player.interaction.RaycastEngine;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.function.BiConsumer;
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
 * <p>Mob blows land here too: the server judges the hit, and the player's own client — where their
 * health lives — applies it from a {@link PlayerHurtS2C}.
 */
public final class ServerPlayerLocator implements PlayerLocator {

    private final Collection<ServerPlayer> roster;
    /** The world sight is marched through; null sees through everything (tests). */
    private final World world;
    /** Where a mob's hit on a player goes: that player's connection. */
    private final BiConsumer<ServerPlayer, PlayerHurtS2C> hurtSink;

    private RaycastEngine sightRay;

    /** Delivers a hit over the player's own connection. */
    private static final BiConsumer<ServerPlayer, PlayerHurtS2C> SEND = (player, hurt) -> player.send(hurt);

    /** @param roster a live view of the connected players ({@link ServerWorldContext#players()}) */
    public ServerPlayerLocator(Collection<ServerPlayer> roster) {
        this(roster, null, SEND);
    }

    /**
     * @param world    the authoritative world, for line of sight
     * @param hurtSink delivers a mob's hit to the player it landed on
     */
    public ServerPlayerLocator(Collection<ServerPlayer> roster, World world,
                               BiConsumer<ServerPlayer, PlayerHurtS2C> hurtSink) {
        this.roster = roster;
        this.world = world;
        this.hurtSink = hurtSink;
    }

    /** Over {@code world}, delivering hits over each player's connection. */
    public static ServerPlayerLocator of(Collection<ServerPlayer> roster, World world) {
        return new ServerPlayerLocator(roster, world, SEND);
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
        return canSee(nearest(from), eye);
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
        return canSee(byId(id, from), eye);
    }

    @Override
    public boolean hurtPlayer(int id, Vector3f from, float amount, float knockback) {
        return hurt(byId(id, from), from, amount, knockback);
    }

    private boolean canSee(ServerPlayer player, Vector3f eye) {
        if (player == null) {
            return false;
        }
        if (world == null) {
            return true;
        }
        Vector3f dir = new Vector3f(player.x(), player.y() + CHEST_HEIGHT, player.z()).sub(eye);
        float dist = dir.length();
        if (dist < 1.0e-4f) {
            return true;
        }
        if (sightRay == null) {
            sightRay = new RaycastEngine(null, null, world);
        }
        return sightRay.distanceToFirstSolid(eye, dir.div(dist), dist) >= dist;
    }

    /** The nearest player takes the blow, shoved horizontally away from {@code from}. */
    @Override
    public boolean hurtNearestPlayer(Vector3f from, float amount, float knockback) {
        return hurt(nearest(from), from, amount, knockback);
    }

    private boolean hurt(ServerPlayer target, Vector3f from, float amount, float knockback) {
        if (target == null) {
            return false;
        }
        float dx = target.x() - from.x;
        float dz = target.z() - from.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        float kx = len > 1.0e-4f ? dx / len * knockback : 0f;
        float kz = len > 1.0e-4f ? dz / len * knockback : 0f;
        hurtSink.accept(target, new PlayerHurtS2C(amount, kx, kz));
        return true;
    }

    /** The first live player whose standing body box (grown by {@code margin}) holds {@code point}. */
    @Override
    public boolean hurtPlayerAt(Vector3f point, float margin, float amount) {
        float halfWidth = PlayerConstants.PLAYER_WIDTH * 0.5f + margin;
        for (ServerPlayer player : roster) {
            if (player.isLiveInWorld()
                    && Math.abs(point.x - player.x()) <= halfWidth
                    && Math.abs(point.z - player.z()) <= halfWidth
                    && point.y >= player.y() - margin
                    && point.y <= player.y() + PlayerConstants.PLAYER_HEIGHT + margin) {
                hurtSink.accept(player, new PlayerHurtS2C(amount, 0f, 0f));
                return true;
            }
        }
        return false;
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
