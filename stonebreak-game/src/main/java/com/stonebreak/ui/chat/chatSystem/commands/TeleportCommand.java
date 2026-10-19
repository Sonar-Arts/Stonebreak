package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.List;

/**
 * Command to teleport the player to a world position.
 * Usage: /teleport &lt;x&gt; [y] &lt;z&gt;
 *
 * <p>With three arguments the middle one is the exact Y. With two, Y is resolved to the
 * top of the highest solid block in that column, so {@code /tp 1500 -800} always lands on
 * the surface rather than inside a mountain or under the world.
 *
 * <p>Coordinates are absolute; there is no relative ({@code ~}) form.
 */
public class TeleportCommand implements ChatCommand {

    /**
     * Outer bound on X/Z. Block coordinates feed {@code Math.floorDiv} chunk math as ints,
     * and a float coordinate past this stops resolving whole blocks anyway, so a typo of a
     * dozen digits is rejected rather than dropping the player somewhere incoherent.
     */
    private static final float MAX_HORIZONTAL = 30_000_000f;

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        if (args.length != 2 && args.length != 3) {
            showUsage(messageManager);
            return;
        }

        boolean explicitY = args.length == 3;
        Float x = parseCoord(args[0], "X", messageManager);
        Float z = parseCoord(args[explicitY ? 2 : 1], "Z", messageManager);
        if (x == null || z == null) {
            return;
        }
        if (!withinHorizontalBounds(x, messageManager) || !withinHorizontalBounds(z, messageManager)) {
            return;
        }

        World world = Game.getWorld();
        float y;
        if (explicitY) {
            Float parsedY = parseCoord(args[1], "Y", messageManager);
            if (parsedY == null) {
                return;
            }
            if (parsedY < 0 || parsedY > WorldConfiguration.WORLD_HEIGHT) {
                messageManager.addMessage("Y must be between 0 and "
                    + WorldConfiguration.WORLD_HEIGHT + "!", ChatColors.RED);
                return;
            }
            y = parsedY;
        } else {
            y = surfaceY(world, x, z);
        }

        Player player = Game.getPlayer();
        player.teleport(x, y, z);

        messageManager.addMessage(String.format("Teleported to %.1f, %.1f, %.1f", x, y, z),
            ChatColors.GREEN);
    }

    @Override
    public String getName() {
        return "teleport";
    }

    @Override
    public List<String> getAliases() {
        return List.of("tp");
    }

    @Override
    public String getDescription() {
        return "Teleport to a position (/teleport <x> [y] <z>; omit y to land on the surface)";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    /**
     * Feet Y for the highest solid block of the column at {@code (x, z)}.
     *
     * <p>A resident chunk is scanned directly, so blocks the player built are honoured. The
     * destination of a long teleport is not resident, and a block read there would report
     * air for the whole column, so the generator is asked instead —
     * {@code carvedSurfaceHeight} is one past the topmost solid block of the column with
     * caves, ravines and overhangs already taken out of it, which is exactly the feet Y.
     * That query builds the chunk's carve masks on a miss, so it costs a brief hitch once
     * per chunk.
     */
    private float surfaceY(World world, float x, float z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);

        Chunk chunk = world.getChunkIfLoaded(
            Math.floorDiv(bx, WorldConfiguration.CHUNK_SIZE),
            Math.floorDiv(bz, WorldConfiguration.CHUNK_SIZE));
        if (chunk != null) {
            int top = Math.min(chunk.getHighestNonAirY(), WorldConfiguration.WORLD_HEIGHT - 1);
            for (int y = top; y >= 0; y--) {
                BlockType block = world.getBlockAt(bx, y, bz);
                if (block != null && block.isSolid()) {
                    return y + 1;
                }
            }
        }

        return world.terrain().carvedSurfaceHeight(bx, bz);
    }

    /** Parses one coordinate, reporting the offending axis by name on failure. */
    private Float parseCoord(String arg, String axis, ChatMessageManager messageManager) {
        try {
            float value = Float.parseFloat(arg);
            if (!Float.isFinite(value)) {
                messageManager.addMessage("Invalid " + axis + " coordinate: " + arg, ChatColors.RED);
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            messageManager.addMessage("Invalid " + axis + " coordinate: " + arg, ChatColors.RED);
            return null;
        }
    }

    private boolean withinHorizontalBounds(float value, ChatMessageManager messageManager) {
        if (Math.abs(value) > MAX_HORIZONTAL) {
            messageManager.addMessage("Coordinates must be within +/-"
                + (long) MAX_HORIZONTAL + "!", ChatColors.RED);
            return false;
        }
        return true;
    }

    private void showUsage(ChatMessageManager messageManager) {
        messageManager.addMessage("Usage: /teleport <x> [y] <z>", ChatColors.YELLOW);
        messageManager.addMessage("  Omit y to land on the highest ground at that column.",
            ChatColors.LIGHT_GRAY);
        messageManager.addMessage("Examples:", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /teleport 1500 -800       (surface)", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /teleport 1500 90 -800    (exact height)", ChatColors.LIGHT_GRAY);
    }
}
