package com.stonebreak.network.server.handlers;

import com.stonebreak.items.Item;
import com.stonebreak.items.ItemLookup;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;

/**
 * Server-side chat commands. The client forwards every command flagged
 * {@code executedOnServer()} as a plain chat line; this handler checks the SERVER's
 * cheats flag (never the client's), executes, and answers only the sender — remote
 * peers see nothing.
 */
public final class ServerCommandHandler {

    private static final int MAX_OBTAIN_COUNT = 999;

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

    private static void reply(ServerPlayer sp, String message) {
        sp.send(new ChatMessageS2C(sp.playerId(), "Server", message), false);
    }
}
