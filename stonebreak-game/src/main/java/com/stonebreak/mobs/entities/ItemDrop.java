package com.stonebreak.mobs.entities;

import com.stonebreak.items.ItemType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Entity representing a dropped item that can be picked up by the player.
 * Uses 2D billboard sprite rendering through the DropRenderer system with realistic physics.
 */
public class ItemDrop extends Entity {
    
    private final ItemStack itemStack;
    private float despawnTimer;
    private float pickupDelay;
    private static final float DESPAWN_TIME = 300.0f; // 5 minutes
    private static final float PICKUP_RANGE = 1.5f;
    /** See {@link BlockDrop#PICKUP_DELAY} — same multiplayer fix. */
    private static final float PICKUP_DELAY = 0.5f;
    
    // Nearby same-kind drops merge into one (see mergeNearbyDrops)
    private static final float MERGE_RANGE = 2.0f;
    private int stackCount = 1; // How many items this drop holds
    
    // Physics constants for drops (custom values independent of Entity base class)
    private static final float DROP_GRAVITY = 12.0f; // Moderate gravity instead of inheriting -40f from Entity
    private static final float DROP_AIR_RESISTANCE = 0.995f; // Much less air resistance than default 0.98f
    private static final float DROP_BOUNCE = 0.4f; // Slightly more bouncy
    private static final float DROP_FRICTION = 0.9f; // Less friction when on ground
    
    /**
     * Creates a new item drop at the specified position.
     */
    public ItemDrop(World world, Vector3f position, ItemStack itemStack) {
        super(world, position);
        this.itemStack = itemStack;
        this.despawnTimer = DESPAWN_TIME;
        this.pickupDelay = PICKUP_DELAY;

        this.stackCount = itemStack.getCount();
        
        // Set drop-specific properties from EntityType
        EntityType dropType = EntityType.ITEM_DROP;
        this.health = dropType.getMaxHealth();
        this.maxHealth = dropType.getMaxHealth();
        this.width = dropType.getWidth();
        this.height = dropType.getHeight();
        this.length = dropType.getLength();
        
        // Add initial velocity for realistic drop behavior
        this.velocity.set(
            (float)(Math.random() - 0.5) * 2.0f, // Random X velocity
            2.0f,                                  // Upward Y velocity
            (float)(Math.random() - 0.5) * 2.0f   // Random Z velocity
        );
    }
    
    /**
     * Creates a new item drop for a single item type.
     */
    public ItemDrop(World world, Vector3f position, ItemType itemType, int count) {
        this(world, position, new ItemStack(itemType, count));
    }
    
    /**
     * Updates the item drop's physics, despawn timer, and pickup detection.
     */
    @Override
    public void update(float deltaTime) {
        if (!alive) return;

        // Update despawn timer
        despawnTimer -= deltaTime;
        if (despawnTimer <= 0) {
            alive = false;
            return;
        }

        if (pickupDelay > 0f) pickupDelay -= deltaTime;

        // Apply physics with drop-specific modifications
        applyDropPhysics(deltaTime);

        // Check for player pickup
        if (pickupDelay <= 0f) checkPlayerPickup();
        
        mergeNearbyDrops();
        
        // Prevent drops from falling into the void
        if (position.y < -10) {
            alive = false;
        }
    }
    
    /**
     * Network shadows skip {@link #update} entirely, but the renderer's bob + spin
     * animation derives from {@link #getAge()} — advance it here so replicated drops
     * still hover and spin on clients.
     */
    @Override
    public void updateClientVisuals(float deltaTime) {
        age += deltaTime;
    }

    /**
     * Applies physics specific to dropped items with custom floaty behavior.
     */
    private void applyDropPhysics(float deltaTime) {
        age += deltaTime;

        // Apply custom light gravity for extremely floaty effect
        if (!onGround && !inWater) {
            velocity.y -= DROP_GRAVITY * deltaTime;
        }
        
        // Apply minimal air resistance for floaty movement
        velocity.mul(DROP_AIR_RESISTANCE);
        
        // Update position based on velocity
        Vector3f movement = new Vector3f(velocity).mul(deltaTime);
        Vector3f oldPosition = new Vector3f(position);
        position.add(movement);
        
        // Simple collision detection with world
        checkWorldCollision(oldPosition);
        
        // Apply reduced friction if on ground
        if (onGround) {
            velocity.x *= DROP_FRICTION;
            velocity.z *= DROP_FRICTION;
            
            // Stop very small movements (lower threshold for floaty effect)
            if (Math.abs(velocity.x) < 0.005f) velocity.x = 0;
            if (Math.abs(velocity.z) < 0.005f) velocity.z = 0;
        }
    }
    
    /**
     * Simple collision detection with the world — one shared pass for both drops
     * (issue #265), backed by the single BlockShape solidity/height rule so drops
     * fall through flowers, wildgrass, torches and water and rest correctly on
     * snow layers and stairs.
     */
    private void checkWorldCollision(Vector3f oldPosition) {
        DropWorldCollision.Result result = DropWorldCollision.resolveGround(
                world, position, oldPosition, width, length, height);
        inWater = result.inWater();

        switch (result.outcome()) {
            case GROUNDED -> {
                position.y = result.restCentreY();
                applyBounce();
            }
            case ESCAPED -> {
                // Escape also zeroes the horizontal speed — keeping it made the drop
                // bounce off the wall and back (onGround=false also turns off ground
                // friction, so anything still pushing it re-embeds it forever).
                position.set(result.escapeCentre());
                velocity.x = 0;
                velocity.z = 0;
                velocity.y = 0; // No pop on escape — the resolver guaranteed a passable cell
                onGround = false;
            }
            case AIRBORNE -> onGround = false;
        }
    }

    /**
     * Floaty bounce off the surface the drop came to rest on. The rest snap is done by
     * {@link DropWorldCollision}; this applies the bounce its resolution leaves in
     * {@code velocity.y}.
     */
    private void applyBounce() {
        onGround = true;

        // Custom bounce effect for floaty drops
        if (velocity.y < 0) {
            velocity.y = -velocity.y * DROP_BOUNCE;
            if (Math.abs(velocity.y) < 0.2f) {
                velocity.y = 0; // Stop small bounces (lower threshold)
            }
        }
    }

    /**
     * Checks if the player is close enough to pick up this drop.
     */
    private void checkPlayerPickup() {
        if (world == null) return;
        // Drops are authoritative SERVER entities; the client holds only render shadows, which
        // must never self-collect. Pickup is server-authoritative: the nearest player in range
        // with room takes what fits — the local client adds it on receipt of GiveItemS2C — and
        // the rest stays here (#372); the drop despawns once it is empty. The stack's state
        // goes with it (#342): a picked-up water bucket is still full.
        if (isNetworkShadow()) return;
        int taken = com.stonebreak.network.MultiplayerSession.tryServerPickup(position, PICKUP_RANGE,
                com.stonebreak.network.packet.NetItemStack.of(itemStack, stackCount));
        if (taken >= stackCount) {
            alive = false;
        } else if (taken > 0) {
            stackCount -= taken;
        }
    }
    
    /**
     * Item drops don't render themselves directly - they use the DropRenderer system.
     */
    @Override
    public void render(Renderer renderer) {
        // Rendering is handled by DropRenderer in the rendering pipeline
        // This method is kept for Entity interface compliance but is not used
    }
    
    @Override
    public EntityType getType() {
        return EntityType.ITEM_DROP;
    }
    
    /**
     * Gets the item stack that this drop represents.
     */
    public ItemStack getItemStack() {
        return itemStack;
    }
    
    /**
     * Gets the item type from the item stack.
     */
    public ItemType getItemType() {
        return itemStack != null ? itemStack.asItemType() : null;
    }
    
    /**
     * Gets the count of items in this drop.
     */
    public int getCount() {
        return itemStack != null ? itemStack.getCount() : 0;
    }
    
    public float getDespawnTimer() { return despawnTimer; }
    public void setDespawnTimer(float timer) { this.despawnTimer = timer; }

    /**
     * Gets the remaining despawn time in seconds.
     */
    public float getRemainingTime() {
        return despawnTimer;
    }
    
    /**
     * Checks if this drop can be picked up by a player at the given position.
     */
    public boolean canPickup(Vector3f playerPosition) {
        return position.distance(playerPosition) <= PICKUP_RANGE;
    }
    
    /**
     * Attempts to pick up this drop and add it to a player's inventory.
     * Returns true if successful, false if inventory is full or other issues.
     */
    public boolean pickup(com.stonebreak.player.Player player) {
        if (player == null || !alive) {
            return false;
        }
        
        com.stonebreak.items.Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        
        // Create ItemStack using the original item (preserves ItemType/BlockType distinction + SBO state)
        com.stonebreak.items.ItemStack itemStack = new com.stonebreak.items.ItemStack(this.itemStack.getItem(), stackCount, this.itemStack.getState());

        // Try to add to inventory
        if (inventory.addItem(itemStack)) {
            // Successfully picked up - play sound and mark as dead
            com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
            if (game != null) {
                com.openmason.engine.audio.SoundSystem soundSystem = game.getSoundSystem();
                if (soundSystem != null) {
                    soundSystem.playSound("blockpickup");
                }
            }
            alive = false;
            return true;
        }
        
        return false; // Inventory was full
    }
    
    /**
     * Attempts to merge this drop with another item drop of the same type.
     * Returns true if successful, false if they can't be merged.
     */
    public boolean mergeWith(ItemDrop other) {
        if (other == null || !other.isAlive() || !this.isAlive()) {
            return false;
        }
        
        if (this.itemStack.canStackWith(other.itemStack)) {
            int totalCount = this.itemStack.getCount() + other.itemStack.getCount();
            int maxStack = this.itemStack.getMaxStackSize();
            
            if (totalCount <= maxStack) {
                // Merge completely
                this.itemStack.setCount(totalCount);
                other.alive = false;
                return true;
            } else {
                // Partial merge
                this.itemStack.setCount(maxStack);
                other.itemStack.setCount(totalCount - maxStack);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Merges nearby drops of the same item AND state into this one (#372) — see
     * {@link BlockDrop}'s {@code mergeNearbyDrops}: the absorbed drop's count moves here and
     * it is removed, so no item can be picked up twice. Matching uses the item's identity
     * (a block-stack item drop has no {@code ItemType}, so the old {@code getItemType()}
     * comparison lumped every block stack together) and its state (a water bucket never
     * merges into empty buckets).
     */
    private void mergeNearbyDrops() {
        if (world == null || !alive) return;

        // THIS world's entity manager, never the Game singleton: the singleton always
        // resolves to the client render world, so an authoritative server drop scanning
        // it would find (and absorb) its own replicated network shadow.
        com.stonebreak.mobs.entities.EntityManager entityManager = world.getEntityManager();
        if (entityManager == null) return;

        int maxStack = itemStack.getMaxStackSize();
        for (Entity entity : entityManager.getAllEntities()) {
            if (stackCount >= maxStack) return;
            if (entity instanceof ItemDrop other && other != this && other.alive
                    && !other.isNetworkShadow() && sameKind(other)
                    && stackCount + other.stackCount <= maxStack
                    && position.distance(other.position) <= MERGE_RANGE) {
                stackCount += other.stackCount;
                // A fresh toss keeps its lock-out; the merged pile lives as long as either.
                pickupDelay = Math.max(pickupDelay, other.pickupDelay);
                despawnTimer = Math.max(despawnTimer, other.despawnTimer);
                other.alive = false;
            }
        }
    }

    private boolean sameKind(ItemDrop other) {
        com.stonebreak.items.Item item = itemStack.getItem();
        return item.isSameType(other.itemStack.getItem())
                && ItemStack.statesMatch(item, itemStack.getState(), other.itemStack.getState());
    }

    /**
     * Gets how many items this drop holds.
     */
    public int getStackCount() {
        return stackCount;
    }

    /**
     * Sets the initial stack count for this drop.
     * Used when creating drops from inventory stacks with multiple items.
     */
    public void setStackCount(int count) {
        this.stackCount = Math.max(1, count);
    }
    
    /**
     * Factory method to create an item drop.
     */
    public static ItemDrop createDrop(World world, Vector3f position, ItemStack itemStack) {
        return new ItemDrop(world, position, itemStack);
    }
    
    /**
     * Factory method to create an item drop with custom initial velocity.
     */
    public static ItemDrop createDropWithVelocity(World world, Vector3f position, ItemStack itemStack, Vector3f initialVelocity) {
        ItemDrop drop = new ItemDrop(world, position, itemStack);
        drop.velocity.set(initialVelocity);
        return drop;
    }

    /** Extend the pickup lock-out (a deliberate toss must not be instantly re-collected). */
    public void setPickupDelay(float seconds) {
        this.pickupDelay = seconds;
    }

    /**
     * Factory method to create a simple item drop from item type and count.
     */
    public static ItemDrop createDrop(World world, Vector3f position, ItemType itemType, int count) {
        return new ItemDrop(world, position, itemType, count);
    }
}