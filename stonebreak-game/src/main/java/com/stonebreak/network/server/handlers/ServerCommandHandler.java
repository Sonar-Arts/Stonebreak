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
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.pack.GoblinPack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
            case "goblins" -> handleGoblins(sp, parts, ctx);
            case "pvp" -> handlePvp(sp, parts, ctx);
            case "invincible" -> handleInvincible(sp, parts, ctx);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * /pvp [on|off] — whether players may hurt each other on this world (issue #343). With no
     * argument, reports the rule. Changing it is the host's call, or anyone's while the world's
     * cheats are on; the rule persists with the world and every client is told.
     */
    private void handlePvp(ServerPlayer sp, String[] args, ServerWorldContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (args.length < 2) {
            reply(sp, "PvP is " + (level.pvpEnabled() ? "on" : "off") + " on this world.");
            return;
        }
        Boolean enable = switch (args[1].toLowerCase()) {
            case "on", "true", "enable" -> true;
            case "off", "false", "disable" -> false;
            default -> null;
        };
        if (enable == null) {
            reply(sp, "Usage: /pvp [on|off]");
            return;
        }
        if (!sp.isLocal() && !level.cheatsEnabled()) {
            reply(sp, "Only the host can change PvP while cheats are off.");
            return;
        }
        level.setPvpEnabled(enable);
        ctx.broadcast(new com.stonebreak.network.packet.world.PvpStateS2C(enable), false);
        ctx.broadcast(new ChatMessageS2C(-1, "Server",
                sp.username() + " turned PvP " + (enable ? "on." : "off.")), false);
    }

    /**
     * /invincible [on|off] — the sender takes no damage (falls, cactus, fire, mobs, arrows,
     * other players) until it is turned off or they leave; no argument toggles. Turning it on
     * needs the SERVER's cheats flag; turning it off never does.
     */
    private void handleInvincible(ServerPlayer sp, String[] args, ServerWorldContext ctx) {
        boolean enable;
        if (args.length < 2) {
            enable = !sp.invincible();
        } else {
            Boolean parsed = switch (args[1].toLowerCase()) {
                case "on", "true", "enable", "1" -> true;
                case "off", "false", "disable", "0" -> false;
                default -> null;
            };
            if (parsed == null) {
                reply(sp, "Usage: /invincible [on|off]");
                return;
            }
            enable = parsed;
        }
        ServerLevel level = ctx.serverLevel();
        if (enable && (level == null || !level.cheatsEnabled())) {
            reply(sp, "Cheats are not enabled on this world.");
            return;
        }
        sp.setInvincible(enable);
        reply(sp, enable ? "Invincibility enabled — you take no damage." : "Invincibility disabled.");
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
        org.joml.Vector3d base = new org.joml.Vector3d(
                sp.x() + Math.cos(yaw) * Math.cos(pitch) * SPAWN_DISTANCE,
                sp.y() + Math.sin(pitch) * SPAWN_DISTANCE,
                sp.z() + Math.sin(yaw) * Math.cos(pitch) * SPAWN_DISTANCE);
        int spawned = 0;
        List<Entity> group = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Jitter x/z slightly so multiple spawns don't stack on a single point.
            float jitterX = count > 1 ? (float) (Math.random() - 0.5) * 2.0f : 0f;
            float jitterZ = count > 1 ? (float) (Math.random() - 0.5) * 2.0f : 0f;
            Entity entity = em.spawnEntity(type, new org.joml.Vector3d(base).add(jitterX, 0.0, jitterZ));
            if (entity != null) {
                entity.setCommandSpawned(true);
                group.add(entity);
                spawned++;
            }
        }
        if (!group.isEmpty()) {
            group.get(0).onSpawnedWithGroup(group); // goblins spawned together are one pack
        }

        if (spawned == count) {
            reply(sp, "Spawned " + spawned + "x " + type.getDisplayName());
        } else if (spawned > 0) {
            reply(sp, "Spawned " + spawned + "/" + count + "x " + type.getDisplayName() + " (some failed)");
        } else {
            reply(sp, "Failed to spawn " + type.getDisplayName() + "!");
        }
    }

    /** How far /goblins looks for packs. */
    private static final float GOBLINS_RADIUS = 64.0f;

    /** Most goblins one /goblins grow adds: up to the camp cap from an empty camp. */
    private static final int MAX_GROW = com.stonebreak.mobs.goblin.camp.CampGrowth.MAX_POPULATION;

    /**
     * /goblins — the goblin packs near the sender, for watching hunts (cheats only).
     * /goblins grow &lt;n&gt; — n more goblins for the nearest camp (up to its cap), to watch it build.
     */
    private void handleGoblins(ServerPlayer sp, String[] parts, ServerWorldContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !level.cheatsEnabled()) {
            reply(sp, "Cheats are not enabled on this world.");
            return;
        }
        if (parts.length >= 2 && parts[1].equalsIgnoreCase("grow")) {
            growNearestCamp(sp, parts, ctx);
            return;
        }
        EntityManager em = ctx.entityManager();
        if (em == null) {
            return;
        }
        org.joml.Vector3d at = new org.joml.Vector3d(sp.x(), sp.y(), sp.z());
        Set<GoblinPack> packs = new LinkedHashSet<>();
        for (Entity e : em.getEntitiesInRange(at, GOBLINS_RADIUS)) {
            if (e instanceof Goblin g) {
                packs.add(g.pack());
            }
        }
        if (packs.isEmpty()) {
            reply(sp, "No goblins within " + (int) GOBLINS_RADIUS + " blocks.");
            return;
        }
        for (GoblinPack pack : packs) {
            Goblin leader = pack.leader();
            if (leader == null) {
                continue;
            }
            var quarry = pack.quarry();
            var camp = pack.camp();
            String home = camp != null
                    ? String.format("camp at %d %d %d (%d goblins, stash %d, carried %d, on fire %d, "
                                    + "built %d stakes / %d bedrolls%s)",
                            camp.fire().x(), camp.fire().y(), camp.fire().z(), camp.population(),
                            camp.stash().food(), pack.carried().food(), onFire(ctx, camp.fire()),
                            camp.pieceCount(com.stonebreak.blocks.BlockType.PALISADE),
                            camp.pieceCount(com.stonebreak.blocks.BlockType.BEDROLL),
                            camp.repairWait() > 0 ? String.format(", repairs in %.0fs", camp.repairWait()) : "")
                    : pack.plan() != null
                            ? String.format("making camp at %d %d %d%s", pack.plan().site().x, pack.plan().site().y,
                                    pack.plan().site().z, pack.plan().needsDigging() ? " (digging)" : "")
                            : "no camp";
            if (pack.enemy() != null) {
                home += ", fighting a pack of " + pack.enemy().size();
            }
            if (pack.raidTarget() != null) {
                home += String.format(", raiding %d %d %d", pack.raidTarget().x(), pack.raidTarget().y(),
                        pack.raidTarget().z());
            }
            var ai = leader.getAI();
            String doing = (ai == null ? "?" : ai.activeBehaviorName() + "/" + ai.nav().status())
                    + (leader.pack() == pack ? "" : " UNTICKED(leader in another pack)")
                    + (leader.isAlive() ? "" : " DEAD-LEADER");
            replyAndLog(sp, String.format("Pack of %d @ %.0f %.0f %.0f [%s]: %s, food %.1f, quarry %s, rest %.0fs, %s",
                    pack.size(), leader.getX(), leader.getY(), leader.getZ(), doing, pack.task(),
                    pack.ledger().foodInHand(pack.food()),
                    quarry != null ? quarry.getType().getDisplayName() : "none",
                    pack.ledger().cooldown(), home));
        }
    }

    /** /goblins grow &lt;n&gt;: the nearest camp gains n goblins, brought in at its fire. */
    private void growNearestCamp(ServerPlayer sp, String[] parts, ServerWorldContext ctx) {
        int n;
        try {
            n = parts.length >= 3 ? Integer.parseInt(parts[2]) : 1;
        } catch (NumberFormatException e) {
            reply(sp, "Usage: /goblins grow <count>");
            return;
        }
        if (n <= 0 || n > MAX_GROW || ctx.world() == null) {
            reply(sp, "Count must be 1 to " + MAX_GROW + ".");
            return;
        }
        com.stonebreak.mobs.goblin.camp.GoblinCamp nearest = null;
        float best = GOBLINS_RADIUS * GOBLINS_RADIUS;
        for (var camp : ctx.world().goblinCamps().camps()) {
            float dx = (float) (camp.fire().x() + 0.5 - sp.x());
            float dz = (float) (camp.fire().z() + 0.5 - sp.z());
            if (dx * dx + dz * dz < best) {
                best = dx * dx + dz * dz;
                nearest = camp;
            }
        }
        if (nearest == null) {
            replyAndLog(sp, "No goblin camp within " + (int) GOBLINS_RADIUS + " blocks.");
            return;
        }
        int added = 0;
        java.util.Random random = new java.util.Random();
        while (added < n && nearest.population() < com.stonebreak.mobs.goblin.camp.CampGrowth.MAX_POPULATION) {
            nearest.addWaiting(com.stonebreak.mobs.goblin.GoblinLoadout.roll(random));
            added++;
        }
        replyAndLog(sp, String.format("Camp at %d %d %d grows by %d to %d goblins.", nearest.fire().x(),
                nearest.fire().y(), nearest.fire().z(), added, nearest.population()));
    }

    /** A /goblins line also goes to the server log, where a scripted run can read it. */
    private static void replyAndLog(ServerPlayer sp, String message) {
        System.out.println("[goblins] " + message);
        reply(sp, message);
    }

    private static int onFire(ServerWorldContext ctx, com.openmason.engine.util.BlockPos fire) {
        var state = ctx.world() == null ? null : ctx.world().getCampfireRegistry().get(fire);
        return state == null ? -1 : state.contents().size();
    }

    private static void reply(ServerPlayer sp, String message) {
        sp.send(new ChatMessageS2C(sp.playerId(), "Server", message), false);
    }
}
