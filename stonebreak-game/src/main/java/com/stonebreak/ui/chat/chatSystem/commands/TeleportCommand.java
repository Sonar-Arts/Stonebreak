package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;
import com.stonebreak.world.World;
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
     * Outer bound on X/Z: world positions are doubles relative to the world's frame origin and stay accurate to
     * well under a millimetre out to 2^40 blocks, so a typo of a dozen digits is rejected rather than dropping
     * the player somewhere incoherent. (Terrain noise is float and only meaningful to about +-2^23.)
     */
    static final double MAX_HORIZONTAL = (double) (1L << 40);

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
        Double x = parseCoord(args[0], "X", messageManager);
        Double z = parseCoord(args[explicitY ? 2 : 1], "Z", messageManager);
        if (x == null || z == null) {
            return;
        }
        if (!withinHorizontalBounds(x, messageManager) || !withinHorizontalBounds(z, messageManager)) {
            return;
        }

        World world = Game.getWorld();
        double y;
        if (explicitY) {
            Double parsedY = parseCoord(args[1], "Y", messageManager);
            if (parsedY == null) {
                return;
            }
            if (!withinVerticalBounds(parsedY)) {
                messageManager.addMessage("Y must be between " + WorldConfiguration.MIN_Y + " and "
                    + WorldConfiguration.MAX_Y + "!", ChatColors.RED);
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
     * <p>Cells the world knows (loaded cubes, and known air above the column's top) are scanned from the
     * surface tracker's top down, so blocks the player built are honoured. As soon as the scan reaches a
     * cell the world does not know (the destination of a long teleport is not resident), the generator is
     * asked instead: its carved height is one past the topmost solid block of the column with caves,
     * ravines and overhangs already taken out of it, which is exactly the feet Y.
     */
    static long surfaceY(World world, double x, double z) {
        long bx = (long) Math.floor(x);
        long bz = (long) Math.floor(z);

        long top = Math.min(world.topY(bx, bz) - 1, WorldConfiguration.MAX_Y - 1);
        for (long y = top; y >= WorldConfiguration.MIN_Y; y--) {
            if (!world.isKnown(bx, y, bz)) {
                break;
            }
            BlockType block = world.getBlockAt(bx, y, bz);
            if (block != null && block.isSolid()) {
                return y + 1;
            }
        }

        long carved = world.terrain().queryColumn(bx, bz).carvedHeight();
        return Math.max(WorldConfiguration.MIN_Y + 1, Math.min(carved, WorldConfiguration.MAX_Y));
    }

    /** Parses one coordinate, reporting the offending axis by name on failure. */
    static Double parseCoord(String arg, String axis, ChatMessageManager messageManager) {
        try {
            double value = Double.parseDouble(arg);
            if (!Double.isFinite(value)) {
                messageManager.addMessage("Invalid " + axis + " coordinate: " + arg, ChatColors.RED);
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            messageManager.addMessage("Invalid " + axis + " coordinate: " + arg, ChatColors.RED);
            return null;
        }
    }

    static boolean withinVerticalBounds(double y) {
        return y >= WorldConfiguration.MIN_Y && y <= WorldConfiguration.MAX_Y;
    }

    private boolean withinHorizontalBounds(double value, ChatMessageManager messageManager) {
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
        messageManager.addMessage("  y runs from " + WorldConfiguration.MIN_Y + " to " + WorldConfiguration.MAX_Y + ".",
            ChatColors.LIGHT_GRAY);
        messageManager.addMessage("Examples:", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /teleport 1500 -800       (surface)", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /teleport 1500 90 -800    (exact height)", ChatColors.LIGHT_GRAY);
        messageManager.addMessage("  /teleport 0 -300 0        (deep caves)", ChatColors.LIGHT_GRAY);
    }
}
