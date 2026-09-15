package com.stonebreak.util;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.drops.BlockDropTables;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.player.Player;
import com.stonebreak.player.Camera;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Utility class for creating and managing item and block drops in the world.
 * Handles the creation of drop entities when blocks are broken or items are dropped.
 */
public class DropUtil {
    
    // Constants for drop physics
    private static final float DROP_SPREAD_RADIUS = 0.5f;
    private static final float LEAF_BANANA_DROP_CHANCE = 0.05f;
    private static final float DROP_VELOCITY_MIN = 1.0f;
    private static final float DROP_VELOCITY_MAX = 3.0f;
    private static final float DROP_HEIGHT_OFFSET = 0.5f;
    private static final float DROP_SPIT_SPEED_MIN = 0.5f;
    private static final float DROP_SPIT_SPEED_MAX = 1.5f;
    private static final float DROP_SPIT_UP_MIN = 0.25f;
    private static final float DROP_SPIT_UP_MAX = 0.6f;

    /**
     * Creates a block drop at the specified position.
     * Used when blocks are broken by mining.
     */
    public static void createBlockDrop(World world, Vector3f position, BlockType blockType) {
        createBlockDrop(world, position, blockType, null);
    }

    /**
     * Full overload: with {@code preferencePoint} (usually the breaker's position, issue
     * #225) the drop spawns into the adjacent passable cell nearest the breaker and spits
     * out toward it. Without one (mob deaths, inventory spills, furnace contents) the
     * legacy scatter + random pop is kept — the resolved path is a break-drop rule.
     */
    public static void createBlockDrop(World world, Vector3f position, BlockType blockType, Vector3f preferencePoint) {
        if (blockType == null || blockType == BlockType.AIR || world == null) {
            return;
        }

        SpawnAndVelocity spawn = chooseSpawn(world, position, preferencePoint);
        BlockDrop drop = BlockDrop.createDropWithVelocity(world, spawn.position(), blockType, spawn.velocity());

        // Route through the passed world's EntityManager (NOT Game.getEntityManager(), which
        // always resolves to the client render world in the two-world model). On a server-thread
        // break this puts the drop into the authoritative EntityManager whose spawn listener
        // emits EntitySpawnS2C to every connected client.
        EntityManager entityManager = world.getEntityManager();
        if (entityManager != null) {
            entityManager.addEntity(drop);
        }
    }

    /**
     * Creates multiple block drops for blocks that drop multiple items (like snow layers).
     */
    public static void createBlockDrops(World world, Vector3f position, BlockType blockType, int count) {
        createBlockDrops(world, position, blockType, count, null);
    }

    /**
     * Full overload: {@code preferencePoint} biases the spawn cell (see
     * {@link #createBlockDrop(World, Vector3f, BlockType, Vector3f)}).
     */
    public static void createBlockDrops(World world, Vector3f position, BlockType blockType, int count,
                                        Vector3f preferencePoint) {
        for (int i = 0; i < count; i++) {
            createBlockDrop(world, position, blockType, preferencePoint);
        }
    }

    /** Spawn resting position + initial velocity chosen for one drop creation. */
    private record SpawnAndVelocity(Vector3f position, Vector3f velocity) {
    }

    /**
     * Issue #225 spawn rule for break drops with a known breaker: the adjacent passable
     * cell nearest the breaker, spitting out toward it. Without a breaker (non-break
     * callers: mob deaths, inventory spills, furnace contents) — and for a fully-enclosed
     * break cell — the legacy scatter + random pop is kept, which goes through the drops'
     * existing ground check for landing.
     */
    private static SpawnAndVelocity chooseSpawn(World world, Vector3f position, Vector3f preferencePoint) {
        if (preferencePoint != null) {
            int cellX = (int) Math.floor(position.x);
            int cellY = (int) Math.floor(position.y);
            int cellZ = (int) Math.floor(position.z);

            // Issue #225: spawn into the nearest adjacent passable cell (air or non-collidable,
            // preferably the one nearest the breaker) instead of blindly offsetting upward — the
            // legacy offset landed drops inside the block above a broken log, and the collision
            // pass then surfaced them trunk-by-trunk.
            Vector3f resolved = DropSpawnResolver.resolveSpawn(world, cellX, cellY, cellZ, preferencePoint);
            if (resolved != null) {
                return new SpawnAndVelocity(resolved, spitOutVelocity(resolved, preferencePoint));
            }
        }
        return new SpawnAndVelocity(scatterPosition(position), randomVelocity());
    }

    private static Vector3f scatterPosition(Vector3f position) {
        return new Vector3f(
            position.x + (float)(Math.random() - 0.5) * DROP_SPREAD_RADIUS,
            position.y + DROP_HEIGHT_OFFSET,
            position.z + (float)(Math.random() - 0.5) * DROP_SPREAD_RADIUS
        );
    }

    private static Vector3f randomVelocity() {
        return new Vector3f(
            (float)(Math.random() - 0.5) * DROP_VELOCITY_MAX,
            DROP_VELOCITY_MIN + (float)Math.random() * (DROP_VELOCITY_MAX - DROP_VELOCITY_MIN),
            (float)(Math.random() - 0.5) * DROP_VELOCITY_MAX
        );
    }

    /**
     * Sideways spit toward the preference point with a low upward pop. The spawn resolver
     * already guarantees a passable resting cell, so no big launch is needed — a large
     * upward pop is what used to carry drops into the trunk above a broken log.
     */
    private static Vector3f spitOutVelocity(Vector3f dropPosition, Vector3f preferencePoint) {
        Vector3f horizontal;
        if (preferencePoint != null) {
            horizontal = new Vector3f(
                preferencePoint.x - dropPosition.x, 0f, preferencePoint.z - dropPosition.z);
        } else {
            horizontal = new Vector3f((float)(Math.random() - 0.5), 0f, (float)(Math.random() - 0.5));
        }
        if (horizontal.lengthSquared() < 1e-6f) {
            horizontal.set((float)(Math.random() - 0.5), 0f, (float)(Math.random() - 0.5));
        }
        float speed = DROP_SPIT_SPEED_MIN + (float) Math.random() * (DROP_SPIT_SPEED_MAX - DROP_SPIT_SPEED_MIN);
        horizontal.normalize().mul(speed);
        float upwardPop = DROP_SPIT_UP_MIN + (float) Math.random() * (DROP_SPIT_UP_MAX - DROP_SPIT_UP_MIN);
        return horizontal.add(0f, upwardPop, 0f);
    }

    /**
     * Creates an item drop at the specified position.
     * Used when items are dropped from inventory or other sources.
     */
    public static void createItemDrop(World world, Vector3f position, ItemStack itemStack) {
        createItemDrop(world, position, itemStack, null);
    }

    /**
     * Full overload: same rule as block drops — with {@code preferencePoint} (issue #225)
     * the drop spawns into the adjacent passable cell nearest the breaker; without one the
     * legacy scatter + random pop is kept (see {@link #chooseSpawn}).
     */
    public static void createItemDrop(World world, Vector3f position, ItemStack itemStack, Vector3f preferencePoint) {
        if (itemStack == null || itemStack.isEmpty() || world == null) {
            return;
        }

        SpawnAndVelocity spawn = chooseSpawn(world, position, preferencePoint);
        ItemDrop drop = ItemDrop.createDropWithVelocity(world, spawn.position(), itemStack, spawn.velocity());

        EntityManager entityManager = world.getEntityManager();
        if (entityManager != null) {
            entityManager.addEntity(drop);
        }
    }

    /**
     * Creates an item drop from item type and count.
     */
    public static void createItemDrop(World world, Vector3f position, ItemType itemType, int count) {
        createItemDrop(world, position, itemType, count, null);
    }

    /**
     * Full overload: {@code preferencePoint} biases the spawn cell (see
     * {@link #createBlockDrop(World, Vector3f, BlockType, Vector3f)}).
     */
    public static void createItemDrop(World world, Vector3f position, ItemType itemType, int count,
                                      Vector3f preferencePoint) {
        if (itemType == null || count <= 0) {
            return;
        }

        ItemStack itemStack = new ItemStack(itemType, count);
        createItemDrop(world, position, itemStack, preferencePoint);
    }
    
    /**
     * Drops an item from the player's current position.
     * Used when player presses Q to drop selected item.
     */
    public static void dropItemFromPlayer(Player player, ItemStack itemStack) {
        if (player == null || itemStack == null || itemStack.isEmpty()) {
            return;
        }

        // Drops are server-authoritative: send the toss intent so the SERVER spawns the drop
        // and replicates it to every client (including us, as a normal shadow). Spawning
        // locally here would create a client-only drop no other player can see —
        // Game.getEntityManager() is the render world's manager in the two-world model.
        if (com.stonebreak.network.MultiplayerSession.sendDropItem(
                itemStack.getBlockTypeId(), itemStack.getCount())) {
            return;
        }

        // Fallback (no live session — shouldn't happen in-world): legacy local spawn.
        // Access world through Game since Player doesn't have getWorld method
        World world = Game.getWorld();
        if (world == null) {
            return;
        }

        // Calculate drop position safely in front of player
        Vector3f playerPosition = player.getPosition();
        // Get player's forward direction from camera
        Vector3f playerForward = player.getCamera().getFront();
        
        Vector3f dropPosition = new Vector3f(playerPosition)
            .add(new Vector3f(playerForward).mul(2.0f)) // Drop 2 blocks in front to avoid player collision
            .add(0, 1.0f, 0); // 1 block above ground for better visibility
        
        // Give the drop some forward velocity
        Vector3f dropVelocity = new Vector3f(playerForward)
            .mul(2.0f) // Reduced forward speed since drop starts further away
            .add(0, 1.0f, 0); // Upward component
        
        // Create appropriate drop type based on item stack content
        if (itemStack.isPlaceable()) {
            BlockType blockType = itemStack.asBlockType();
            if (blockType != null) {
                BlockDrop drop = BlockDrop.createDropWithVelocity(world, dropPosition, blockType, dropVelocity);
                drop.setStackCount(itemStack.getCount()); // Set the actual stack count from the ItemStack
                EntityManager entityManager = Game.getEntityManager();
                if (entityManager != null) {
                    entityManager.addEntity(drop);
                }
            }
        } else {
            ItemDrop drop = ItemDrop.createDropWithVelocity(world, dropPosition, itemStack.copy(), dropVelocity);
            EntityManager entityManager = Game.getEntityManager();
            if (entityManager != null) {
                entityManager.addEntity(drop);
            }
        }
    }
    
    /**
     * Drops a single item from the player's selected hotbar slot.
     * Decrements the stack by 1 and drops it.
     */
    public static void dropSingleItemFromPlayer(Player player) {
        if (player == null) {
            return;
        }
        
        com.stonebreak.items.Inventory inventory = player.getInventory();
        if (inventory == null) {
            return;
        }
        
        ItemStack selectedSlot = inventory.getSelectedHotbarSlot();
        if (selectedSlot == null || selectedSlot.isEmpty()) {
            return;
        }
        
        // Create a copy with count 1 for dropping (preserve SBO state, e.g. filled bucket)
        ItemStack dropStack = selectedSlot.copy();
        dropStack.setCount(1);
        if (!selectedSlot.isPlaceable() && selectedSlot.asItemType() == null) {
            return; // Can't drop unknown item type
        }
        
        // Drop the item
        dropItemFromPlayer(player, dropStack);
        
        // Decrement the inventory slot
        selectedSlot.decrementCount(1);
        
        // Clear slot if empty
        if (selectedSlot.getCount() <= 0) {
            selectedSlot.clear();
        }
    }
    
    /**
     * Drops the entire stack from the player's selected hotbar slot.
     */
    public static void dropEntireStackFromPlayer(Player player) {
        if (player == null) {
            return;
        }
        
        com.stonebreak.items.Inventory inventory = player.getInventory();
        if (inventory == null) {
            return;
        }
        
        ItemStack selectedSlot = inventory.getSelectedHotbarSlot();
        if (selectedSlot == null || selectedSlot.isEmpty()) {
            return;
        }
        
        // Drop the entire stack
        dropItemFromPlayer(player, selectedSlot.copy());
        
        // Clear the slot
        selectedSlot.clear();
    }
    
    /** Spawns each rolled line: blocks as block drops, everything else as item stacks. */
    private static void spawnRolledDrops(World world, Vector3f position,
                                         java.util.List<BlockDropTables.RolledDrop> rolled,
                                         Vector3f preferencePoint) {
        for (BlockDropTables.RolledDrop r : rolled) {
            if (r.item() instanceof BlockType bt) {
                createBlockDrops(world, position, bt, r.count(), preferencePoint);
            } else {
                createItemDrop(world, position, new ItemStack(r.item(), r.count()), preferencePoint);
            }
        }
    }

    /**
     * Built-in fallback drop for a block whose SBO carries no {@code drops} table.
     * Some blocks drop something other than themselves (e.g., stone drops cobblestone).
     * Prefer authoring a drop table in Open Mason over extending this method.
     */
    public static BlockType getBlockDrop(BlockType brokenBlock) {
        if (brokenBlock == null) {
            return null;
        }
        
        // Fluids never drop: a water→air edit reaches the server's break path
        // when a bucket scoops a source (the scoop routes through the block
        // edit funnel), and dropping a WATER block for it is nonsense.
        if (brokenBlock == BlockType.WATER) return null;
        // Mining substitutions: a few blocks drop something other than
        // themselves. Most fall through to the brokenBlock itself.
        if (brokenBlock == BlockType.STONE) return BlockType.COBBLESTONE;
        if (brokenBlock == BlockType.RED_SANDSTONE) return BlockType.RED_SAND_COBBLESTONE;
        if (brokenBlock == BlockType.SANDSTONE) return BlockType.SAND_COBBLESTONE;
        if (brokenBlock == BlockType.GRASS) return BlockType.DIRT;
        // Future: IRON_ORE / COAL_ORE should drop ingot/coal items here once
        // those items exist. Currently they drop themselves via the fallthrough.
        return brokenBlock;
    }
    
    /**
     * Handles block breaking and creates appropriate drops.
     */
    public static void handleBlockBroken(World world, Vector3f position, BlockType brokenBlock) {
        handleBlockBroken(world, position, brokenBlock, null, 0);
    }

    /**
     * Handles block breaking and creates appropriate drops.
     * @param toolItem the item type used to break the block (may be null)
     */
    public static void handleBlockBroken(World world, Vector3f position, BlockType brokenBlock, ItemType toolItem) {
        handleBlockBroken(world, position, brokenBlock, toolItem, 0);
    }

    /**
     * Handles block breaking and creates appropriate drops.
     * @param toolItem the item type used to break the block (may be null)
     * @param snowLayers the number of snow layers when breaking snow (0 when not snow or unknown)
     */
    public static void handleBlockBroken(World world, Vector3f position, BlockType brokenBlock, ItemType toolItem, int snowLayers) {
        handleBlockBroken(world, position, brokenBlock, toolItem, snowLayers, null);
    }

    /**
     * Full overload: {@code preferencePoint} (usually the breaker's position) biases which
     * adjacent passable cell the drops spawn into (issue #225).
     * @param toolItem the item type used to break the block (may be null)
     * @param snowLayers the number of snow layers when breaking snow (0 when not snow or unknown)
     * @param preferencePoint the position drops should spawn nearest to (may be null)
     */
    public static void handleBlockBroken(World world, Vector3f position, BlockType brokenBlock, ItemType toolItem,
                                         int snowLayers, Vector3f preferencePoint) {
        if (world == null || brokenBlock == null || brokenBlock == BlockType.AIR) {
            return;
        }

        // Data-driven path first: a block whose SBO carries a `drops` table
        // (authored in Open Mason) is governed entirely by that table — the
        // tool selects a per-tool override, otherwise the default list rolls.
        // An empty table means "drops nothing". Blocks without a table fall
        // through to the built-in rules below.
        java.util.List<BlockDropTables.RolledDrop> rolled =
                BlockDropTables.roll(brokenBlock, toolItem, java.util.concurrent.ThreadLocalRandom.current());
        if (rolled != null) {
            spawnRolledDrops(world, position, rolled, preferencePoint);
            return;
        }

        // Special handling for snow blocks
        if (brokenBlock == BlockType.SNOW) {
            int layers = snowLayers > 0 ? snowLayers : world.getSnowLayers(
                    (int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
            if (toolItem == ItemType.STONE_SHOVEL || toolItem == ItemType.WOODEN_SHOVEL) {
                createItemDrop(world, position, ItemType.SNOWBALL, layers, preferencePoint);
            } else {
                if (layers > 0) {
                    createBlockDrops(world, position, BlockType.SNOW, layers, preferencePoint);
                }
            }
            return;
        }

        // Get the appropriate drop for this block
        BlockType dropType = getBlockDrop(brokenBlock);
        if (dropType != null) {
            createBlockDrop(world, position, dropType, preferencePoint);
        }

        // Small chance to drop a banana from any leaf block
        if (brokenBlock == BlockType.LEAVES
                || brokenBlock == BlockType.PINE_LEAVES
                || brokenBlock == BlockType.ELM_LEAVES) {
            if (Math.random() < LEAF_BANANA_DROP_CHANCE) {
                createItemDrop(world, position, ItemType.BANANA, 1, preferencePoint);
            }
        }
    }
}