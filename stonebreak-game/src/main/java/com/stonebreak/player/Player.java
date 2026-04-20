package com.stonebreak.player;

import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.combat.AttackController;
import com.stonebreak.player.combat.DeathHandler;
import com.stonebreak.player.combat.FallDamageHandler;
import com.stonebreak.player.combat.HealthController;
import com.stonebreak.player.interaction.BlockBreaker;
import com.stonebreak.player.interaction.BlockPlacer;
import com.stonebreak.player.interaction.ItemDropInteraction;
import com.stonebreak.player.interaction.RaycastEngine;
import com.stonebreak.player.lifecycle.PlayerSpawnService;
import com.stonebreak.player.locomotion.FlightController;
import com.stonebreak.player.locomotion.JumpHandler;
import com.stonebreak.player.locomotion.SwimmingController;
import com.stonebreak.player.physics.CollisionHandler;
import com.stonebreak.player.physics.GroundChecker;
import com.stonebreak.player.physics.MovementController;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;

import static com.stonebreak.player.PlayerConstants.CAMERA_EYE_OFFSET;
import static com.stonebreak.player.PlayerConstants.SPAWN_X;
import static com.stonebreak.player.PlayerConstants.SPAWN_Y;
import static com.stonebreak.player.PlayerConstants.SPAWN_Z;

/**
 * Master controller for the player. Owns the shared {@link PhysicsState}, the camera,
 * the inventory, and a suite of focused subsystems that each handle one concern
 * (physics, locomotion, combat, interaction, lifecycle). Every publicly-callable
 * behavior on the old monolithic Player class is preserved here as a thin delegate.
 */
public class Player {

    private final PhysicsState state;
    private final Camera camera;
    private final Inventory inventory;

    // Physics
    private final CollisionHandler collisionHandler;
    private final GroundChecker groundChecker;
    private final MovementController movement;

    // Locomotion
    private final SwimmingController swimming;
    private final FlightController flight;
    private final JumpHandler jumpHandler;

    // Combat
    private final AttackController attack;
    private final HealthController health;
    private final FallDamageHandler fallDamage;
    private final DeathHandler deathHandler;

    // Interaction
    private final RaycastEngine raycastEngine;
    private final BlockBreaker blockBreaker;
    private final BlockPlacer blockPlacer;
    private final ItemDropInteraction itemDropInteraction;

    // Lifecycle
    private final PlayerSpawnService spawnService;

    public Player(World world) {
        IBlockPlacementService blockPlacementService = new BlockPlacementValidator(world);
        this.state = new PhysicsState();
        this.state.getPosition().set(SPAWN_X, SPAWN_Y, SPAWN_Z);
        this.state.setPreviousY(SPAWN_Y);
        this.camera = new Camera();
        this.inventory = new Inventory();

        this.collisionHandler = new CollisionHandler(state, world);
        this.groundChecker = new GroundChecker(state, collisionHandler);
        this.swimming = new SwimmingController(state, world);
        this.flight = new FlightController(state);
        this.jumpHandler = new JumpHandler(state);
        this.movement = new MovementController(state, camera, collisionHandler, flight, swimming, jumpHandler);

        this.attack = new AttackController();
        this.health = new HealthController();
        this.fallDamage = new FallDamageHandler(state, health);
        this.deathHandler = new DeathHandler(state, health, inventory, camera, world);

        this.raycastEngine = new RaycastEngine(state, camera, world);
        this.blockBreaker = new BlockBreaker(raycastEngine, inventory, attack, world);
        this.blockPlacer = new BlockPlacer(state, raycastEngine, inventory, blockPlacementService, world);
        this.itemDropInteraction = new ItemDropInteraction(state, camera, blockPlacementService, world);

        this.spawnService = new PlayerSpawnService(state, camera, inventory, health, attack,
                blockBreaker, flight, jumpHandler, swimming);
    }

    public void update() {
        if (health.isDead()) {
            deathHandler.processDeathIfNeeded();
            return;
        }

        float dt = Game.getDeltaTime();

        health.updateSpawnProtection(dt, state.isOnGround());
        swimming.updateWaterState();
        swimming.applyAntiFloatingPreIntegration(flight.isFlying(),
                jumpHandler.getLastNormalJumpTime(), jumpHandler.getNormalJumpGracePeriod());
        swimming.applyWaterFlow(flight.isFlying());

        movement.applyGravity();
        movement.integrateAndCollide();
        groundChecker.check();
        movement.applyDamping();

        Vector3f p = state.getPosition();
        camera.setPosition(p.x, p.y + CAMERA_EYE_OFFSET, p.z);

        attack.update(dt);
        blockBreaker.update();

        Game.getSoundSystem().updatePlayerSounds(p, state.getVelocity(), state.isOnGround(), state.isPhysicallyInWater());
        if (Game.getWorld() != null) {
            Game.getSoundSystem().setListenerFromCamera(p, camera.getFront(), camera.getUp());
        }

        fallDamage.update(flight.isFlying());
        deathHandler.processDeathIfNeeded();
    }

    public void processMovement(boolean forward, boolean backward, boolean left, boolean right,
                                boolean jump, boolean shift) {
        movement.processMovement(forward, backward, left, right, jump, shift);
    }

    public void processMouseLook(float xOffset, float yOffset) {
        camera.processMouseMovement(xOffset, yOffset);
    }

    public void processFlightAscent(boolean shift) { flight.processAscent(shift); }
    public void processFlightDescent(boolean shift) { flight.processDescent(shift); }

    // Position / state
    public Vector3f getPosition() { return state.getPosition(); }
    public Vector3f getVelocity() { return state.getVelocity(); }
    public void setVelocity(Vector3f velocity) { state.getVelocity().set(velocity); }
    public boolean isOnGround() { return state.isOnGround(); }
    public void setOnGround(boolean onGround) { state.setOnGround(onGround); }

    public void setPosition(float x, float y, float z) {
        state.getPosition().set(x, y, z);
        camera.setPosition(x, y + CAMERA_EYE_OFFSET, z);
    }

    public void setPosition(Vector3f position) {
        setPosition(position.x, position.y, position.z);
    }

    // Camera / view / inventory
    public Camera getCamera() { return camera; }
    public Inventory getInventory() { return inventory; }
    public Matrix4f getViewMatrix() { return camera.getViewMatrix(); }

    // Health / death
    public float getHealth() { return health.getHealth(); }
    public float getMaxHealth() { return health.getMaxHealth(); }
    public boolean isDead() { return health.isDead(); }
    public int getHearts() { return health.getHearts(); }
    public void setHealth(float h) { health.setHealth(h); }
    public void damage(float amount) { health.damage(amount); }
    public void heal(float amount) { health.heal(amount); }
    public void respawn() { deathHandler.respawn(); }

    // Attack animation
    public boolean isAttacking() { return attack.isAttacking(); }
    public void startAttackAnimation() { attack.startAttackAnimation(); }
    public float getAttackAnimationProgress() { return attack.getAnimationProgress(); }
    public float getRawAttackAnimationProgress() { return attack.getRawAnimationProgress(); }

    // Flight
    public boolean isFlying() { return flight.isFlying(); }
    public void setFlying(boolean flying) { flight.setFlying(flying); }
    public boolean isFlightEnabled() { return flight.isFlightEnabled(); }
    public void setFlightEnabled(boolean enabled) { flight.setFlightEnabled(enabled); }

    // Water
    public boolean isInWater() { return swimming.isInWater(); }

    // Block interaction
    public Vector3i raycast() { return raycastEngine.raycast(); }
    public void placeBlock() { blockPlacer.placeBlock(); }
    public void startBreakingBlock() { blockBreaker.startBreaking(); }
    public void stopBreakingBlock() { blockBreaker.stopBreaking(); }
    public Vector3i getBreakingBlock() { return blockBreaker.getBreakingBlock(); }
    public float getBreakingProgress() { return blockBreaker.getBreakingProgress(); }
    public boolean attemptDropItemInFront(ItemStack itemToDrop) {
        return itemDropInteraction.attemptDropItemInFront(itemToDrop);
    }

    // Lifecycle
    public void giveStartingItems() { spawnService.giveStartingItems(); }
    public void setLoadedFromSave(boolean loaded) { spawnService.setLoadedFromSave(loaded); }

    public void setWorld(World world) {
        collisionHandler.setWorld(world);
        swimming.setWorld(world);
        raycastEngine.setWorld(world);
        blockBreaker.setWorld(world);
        blockPlacer.setWorld(world);
        itemDropInteraction.setWorld(world);
        deathHandler.setWorld(world);
        System.out.println("[WORLD-ISOLATION] Player world reference updated for world switching");
    }
}
