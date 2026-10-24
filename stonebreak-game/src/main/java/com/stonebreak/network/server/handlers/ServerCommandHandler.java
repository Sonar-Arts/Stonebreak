package com.stonebreak.network.server.handlers;

import com.stonebreak.items.Item;
import com.stonebreak.items.ItemLookup;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import org.joml.Vector3f;

/**
 * Server-side chat commands. The client forwards every command flagged
 * {@code executedOnServer()} as a plain chat line; this handler checks the SERVER's
 * cheats flag (never the client's), executes, and answers only the sender — remote
 * peers see nothing.
 */
public final class ServerCommandHandler {

    private static final int MAX_OBTAIN_COUNT = 999;
    private static final int MAX_SPAWN_COUNT = 50;
    /** Distance ahead of the player (along its look direction) that /spawn places mobs. */
    private static final float SPAWN_DISTANCE = 3.0f;

    /**
     * Handles a leading-'/' chat line naming a command the SERVER owns; returns true when it
     * did (the line is consumed). Any other line — including '/' lines the server has no
     * command for — returns false and relays as ordinary chat, as before #334 (the client
     * runs its own commands locally and forwards only the server-executed ones).
     */
    public boolean handleCommand(ServerPlayer sp, String text, ServerWorldContext ctx) {
        if (text == null || !text.startsWith("/")) {
            return false;
        }
        String[] parts = text.substring(1).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return false;
        }
        switch (parts[0].toLowerCase()) {
            case "obtain" -> handleObtain(sp, parts, ctx);
            case "spawn" -> handleSpawn(sp, parts, ctx);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * /obtain &lt;item&gt; [count] — the server-side item give (issue #334). Checked against
     * the SERVER's cheats flag, added to the SERVER's inventory replica (clamped, capacity
     * aware) and synced back; the client's own inventory write is gone.
     */
    private void handleObtain(ServerPlayer sp, String[] args, ServerWorldContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !level.cheatsEnabled()) {
            reply(sp, "Cheats are not enabled on this world.");
            return;
        }
        if (args.length < 2) {
            reply(sp, "Usage: /obtain <stonebreak:item> [count]");
            return;
        }
        Item item = ItemLookup.find(ItemLookup.parseName(args[1]));
        if (item == null) {
            reply(sp, "Unknown item: " + args[1]);
            return;
        }
        int count = 1;
        if (args.length >= 3) {
            try {
                count = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                reply(sp, "Invalid count: " + args[2]);
                return;
            }
            if (count <= 0) {
                reply(sp, "Count must be greater than 0!");
                return;
            }
            if (count > MAX_OBTAIN_COUNT) {
                reply(sp, "Count too large! Maximum is " + MAX_OBTAIN_COUNT);
                return;
            }
        }
        int added = sp.inventory().add(item.getId(), count);
        if (added > 0) {
            sp.send(sp.inventorySync(), false);
        }
        if (added < count) {
            reply(sp, "Could not add all items — inventory may be full (added " + added + " of "
                + count + ").");
        } else {
            reply(sp, "Gave " + count + "x " + item.getName() + " to player");
        }
    }

    /**
     * /spawn &lt;mob&gt; [count] — the server-side mob spawn (issue #341). Checked against the
     * SERVER's cheats flag and spawned into the SERVER world in front of the player's reported
     * position and facing: the mob runs its AI on the server, replicates to every player and is
     * saved with the world, like a naturally spawned one.
     */
    private void handleSpawn(ServerPlayer sp, String[] args, ServerWorldContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !level.cheatsEnabled()) {
            reply(sp, "Cheats are not enabled on this world.");
            return;
        }
        if (args.length < 2) {
            reply(sp, "Usage: /spawn <mob> [count]");
            return;
        }
        EntityType type = EntityType.findGlossaryMob(args[1]);
        if (type == null) {
            reply(sp, "Unknown mob: " + args[1]);
            return;
        }
        int count = 1;
        if (args.length >= 3) {
            try {
                count = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                reply(sp, "Invalid count: " + args[2]);
                return;
            }
            if (count <= 0) {
                reply(sp, "Count must be greater than 0!");
                return;
            }
            if (count > MAX_SPAWN_COUNT) {
                reply(sp, "Count too large! Maximum is " + MAX_SPAWN_COUNT);
                return;
            }
        }
        EntityManager em = ctx.entityManager();
        if (em == null || sp.lastStateNs() == 0L) {
            reply(sp, "Failed to spawn " + type.getDisplayName() + "!");
            return;
        }

        // Camera convention: front = (cos yaw·cos pitch, sin pitch, sin yaw·cos pitch).
        double yaw = Math.toRadians(sp.yaw());
        double pitch = Math.toRadians(sp.pitch());
        Vector3f base = new Vector3f(
                sp.x() + (float) (Math.cos(yaw) * Math.cos(pitch)) * SPAWN_DISTANCE,
                sp.y() + (float) Math.sin(pitch) * SPAWN_DISTANCE,
                sp.z() + (float) (Math.sin(yaw) * Math.cos(pitch)) * SPAWN_DISTANCE);
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            // Jitter x/z slightly so multiple spawns don't stack on a single point.
            float jitterX = count > 1 ? (float) (Math.random() - 0.5) * 2.0f : 0f;
            float jitterZ = count > 1 ? (float) (Math.random() - 0.5) * 2.0f : 0f;
            Entity entity = em.spawnEntity(type, new Vector3f(base).add(jitterX, 0f, jitterZ));
            if (entity != null) {
                entity.setCommandSpawned(true);
                spawned++;
            }
        }

        if (spawned == count) {
            reply(sp, "Spawned " + spawned + "x " + type.getDisplayName());
        } else if (spawned > 0) {
            reply(sp, "Spawned " + spawned + "/" + count + "x " + type.getDisplayName() + " (some failed)");
        } else {
            reply(sp, "Failed to spawn " + type.getDisplayName() + "!");
        }
    }

    private static void reply(ServerPlayer sp, String message) {
        sp.send(new ChatMessageS2C(sp.playerId(), "Server", message), false);
    }
}
