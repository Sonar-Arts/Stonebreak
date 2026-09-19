package com.stonebreak.battletest;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.serialization.JsonPlayerSerializer;
import com.stonebreak.world.save.util.StateConverter;
import java.io.IOException;
import org.joml.Vector3f;

/** Command-only, temporary singleplayer scene. The suspended world's player snapshot remains saveable. */
public final class BattleTestSession {
    private static volatile BattleTestSession active;
    private final Player player;
    private final World returnWorld;
    private final PlayerData savedPlayer;
    private final Vector3f returnVelocity;
    private final boolean returnGrounded;
    private final BattleTestWorld world;
    private final EntityManager entities;
    private final TimeOfDay time = new TimeOfDay(TimeOfDay.SUNSET);
    private final BattleTestMesh mesh;

    private BattleTestSession(Player player, World returnWorld, BattleTestArena arena, BattleTestMesh mesh) {
        this.player = player;
        this.returnWorld = returnWorld;
        // Serialize round trip severs ItemStack references: test inventory/progression must not mutate the
        // save snapshot.
        var serializer = new JsonPlayerSerializer();
        savedPlayer = serializer.deserialize(serializer.serialize(
            StateConverter.toPlayerData(player, Game.getInstance().getCurrentWorldName())));
        returnVelocity = new Vector3f(player.getVelocity());
        returnGrounded = player.isOnGround();
        world = new BattleTestWorld(arena);
        entities = new EntityManager(world);
        this.mesh = mesh;
    }
    public static BattleTestSession current() {
        return active;
    }
    public static boolean isActive() {
        return active != null;
    }
    public BattleTestWorld world() {
        return world;
    }
    public EntityManager entities() {
        return entities;
    }
    public TimeOfDay time() {
        return time;
    }
    public BattleTestMesh mesh() {
        return mesh;
    }

    public static String enter() throws IOException {
        if (active != null)
            return "Already in Frostbound Crucible. Use /battletest reset or /battletest leave.";
        if (MultiplayerSession.getMode() != MultiplayerSession.Mode.SINGLEPLAYER)
            return "Use /battletest inside a singleplayer world.";
        Player player = Game.getPlayer();
        if (player == null || player.isDead() || !MultiplayerSession.isLocalPlayerDataReady())
            return "Load a world and respawn before entering the battle test.";
        BattleTestArena arena = BattleTestArena.load();
        BattleTestMesh mesh = BattleTestMesh.load(arena); // Validate all assets before changing live state.
        BattleTestSession session = new BattleTestSession(player, Game.getWorld(), arena, mesh);
        active = session;
        try {
            player.setWorld(session.world);
            session.reset();
        } catch (RuntimeException failure) {
            leave();
            throw failure;
        }
        return "Frostbound Crucible: cyan = player spawn; violet = Ice Archon spawn. /battletest leave "
            + "returns.";
    }
    /** Called by persistence even while the actual player is temporarily exploring the arena. */
    public static PlayerData persistentPlayerData(Player player) {
        BattleTestSession session = active;
        return session != null && session.player == player ? session.savedPlayer : null;
    }
    public void reset() {
        player.setSpectator(false);
        player.setFlying(false);
        player.resetForScene(world.arena().playerSpawn().position());
        player.getVelocity().zero();
        player.setOnGround(false);
        player.getCamera().setYaw(world.arena().playerSpawn().yaw());
        player.getCamera().setPitch(0);
        player.setHealth(player.getMaxHealth());
    }
    public void update(float dt) {
        if (player.isDead())
            reset();
        player.update();
        entities.update(dt);
        Vector3f p = player.getPosition();
        if (player.isDead() || p.y < -8 || Math.abs(p.x) > 55 || Math.abs(p.z) > 55)
            reset();
    }
    public static boolean leave() {
        BattleTestSession session = active;
        if (session == null)
            return false;
        // Restore first, then resume networking; packets can never publish arena coordinates to the saved
        // world.
        session.player.setWorld(session.returnWorld);
        session.player.resetForScene(session.savedPlayer.getPosition());
        StateConverter.applyPlayerData(session.player, session.savedPlayer);
        session.player.setVelocity(session.returnVelocity);
        session.player.setOnGround(session.returnGrounded);
        active = null;
        session.entities.cleanup();
        session.world.cleanup();
        return true;
    }
}
