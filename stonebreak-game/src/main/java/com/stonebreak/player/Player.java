package com.stonebreak.player;

import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.combat.AttackController;
import com.stonebreak.player.combat.BowController;
import com.stonebreak.player.combat.DeathHandler;
import com.stonebreak.player.combat.FallDamageHandler;
import com.stonebreak.player.combat.HealthController;
import com.stonebreak.player.combat.ManaController;
import com.stonebreak.player.combat.RageTier;
import com.stonebreak.player.combat.StaminaController;
import com.stonebreak.player.combat.arcanist.ArcanistAbilityController;
import com.stonebreak.player.combat.berserker.BerserkerAbilityController;
import com.stonebreak.player.combat.dodge.DodgeController;
import com.stonebreak.player.combat.illusionist.IllusionistAbilityController;
import com.stonebreak.player.combat.ranger.RangerAbilityController;
import com.stonebreak.player.combat.rogue.RogueAbilityController;
import com.stonebreak.player.combat.stealth.StealthController;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.interaction.BlockBreaker;
import com.stonebreak.player.interaction.BlockPlacer;
import com.stonebreak.player.interaction.ItemDropInteraction;
import com.stonebreak.player.interaction.RaycastEngine;
import com.stonebreak.player.lifecycle.PlayerSpawnService;
import com.stonebreak.player.locomotion.FlightController;
import com.stonebreak.player.locomotion.JumpHandler;
import com.stonebreak.player.locomotion.SpectatorController;
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
import static com.stonebreak.player.PlayerConstants.RAGE_T1_DAMAGE_BONUS;
import static com.stonebreak.player.PlayerConstants.RAGE_T2_ATTACK_SPEED_BONUS;
import static com.stonebreak.player.PlayerConstants.RAGE_T3_LIFESTEAL_PCT;
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
    private final SpectatorController spectator;

    // Combat
    private final AttackController attack;
    private final BowController bow;
    private final HealthController health;
    private final StaminaController stamina;
    private final ManaController mana;
    private final FallDamageHandler fallDamage;
    private final DeathHandler deathHandler;
    private final BerserkerAbilityController berserkerAbilities;
    private final RangerAbilityController rangerAbilities;
    private final ArcanistAbilityController arcanistAbilities;
    private final IllusionistAbilityController illusionistAbilities;
    private final RogueAbilityController rogueAbilities;
    private final DodgeController dodge;
    private final StealthController stealth = new StealthController();
    private final java.util.Random critRandom = new java.util.Random();
    private float lastHealthForStealth; // tracks health between frames to detect any damage taken

    // Interaction
    private final RaycastEngine raycastEngine;
    private final BlockBreaker blockBreaker;
    private final BlockPlacer blockPlacer;
    private final ItemDropInteraction itemDropInteraction;

    // Lifecycle
    private final PlayerSpawnService spawnService;

    // RPG
    private final CharacterStats characterStats;

    // Statistics
    private final PlayerStats stats = new PlayerStats();

    // Entity glossary discoveries
    private final EntityDiscoveries discoveries = new EntityDiscoveries();

    // Entity sight tracking (variant discovery via proximity+FOV)
    private final EntitySightingTracker sightingTracker = new EntitySightingTracker();

    // Fishing
    private com.stonebreak.mobs.entities.FishingBobber activeBobber = null;

    // Third-person body model
    public enum Perspective { FIRST_PERSON, THIRD_PERSON }
    private Perspective perspective = Perspective.FIRST_PERSON;
    private float bodyAnimationTime = 0f;
    private float attackEventTime = 0f;  // seconds since attack animation started
    private float jumpEventTime = 0f;    // seconds since jump started
    private final com.stonebreak.mobs.sbe.OverlayAnimState attackOverlay =
            new com.stonebreak.mobs.sbe.OverlayAnimState();
    private static final float WALK_SPEED_THRESHOLD = 0.5f; // blocks/frame
    // Third-person body facing + head look angles. Decoupled from the first-person
    // camera: the camera only supplies a look yaw/pitch; this component decides how
    // the body turns to follow movement and the look direction.
    private final PlayerBodyOrientation bodyOrientation = new PlayerBodyOrientation();

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

        this.attack = new AttackController();
        this.bow = new BowController();
        this.health = new HealthController();
        this.stamina = new StaminaController(0);
        this.mana = new ManaController(0, 0);
        this.spectator = new SpectatorController(state, flight, health);
        this.movement = new MovementController(state, camera, collisionHandler, flight, swimming, jumpHandler, spectator);
        this.fallDamage = new FallDamageHandler(state, health);
        this.deathHandler = new DeathHandler(state, health, inventory, camera, world);
        this.berserkerAbilities = new BerserkerAbilityController();
        this.rangerAbilities = new RangerAbilityController();
        this.arcanistAbilities = new ArcanistAbilityController();
        this.illusionistAbilities = new IllusionistAbilityController();
        this.rogueAbilities = new RogueAbilityController();
        this.dodge = new DodgeController();
        // Momentum passive: a successful dodge grants the Rogue a stack (self-gated on class).
        this.dodge.addDodgeListener(rogueAbilities::onDodgeSuccess);

        this.raycastEngine = new RaycastEngine(state, camera, world);
        this.blockBreaker = new BlockBreaker(raycastEngine, inventory, attack, world);
        this.blockPlacer = new BlockPlacer(state, raycastEngine, inventory, blockPlacementService, world);
        this.itemDropInteraction = new ItemDropInteraction(state, camera, blockPlacementService, world);

        this.characterStats = new CharacterStats(this);
        this.spawnService = new PlayerSpawnService(state, camera, inventory, health, attack,
                blockBreaker, flight, jumpHandler, swimming, characterStats);

        updateDerivedStats();
    }

    public void update() {
        if (health.isDead()) {
            deathHandler.processDeathIfNeeded();
            return;
        }

        float dt = Game.getDeltaTime();

        // Don't fall through terrain that hasn't streamed in yet (async client render world). If
        // the chunk under us isn't rendered — an empty placeholder, or not arrived — hold
        // position instead of dropping into the void. Flight/spectator move freely (no fall).
        if (!flight.isFlying() && !spectator.isActive() && !isGroundChunkReady()) {
            state.getVelocity().set(0f, 0f, 0f);
            Vector3f hp = state.getPosition();
            camera.setPosition(hp.x, hp.y + CAMERA_EYE_OFFSET, hp.z);
            return;
        }

        health.updateSpawnProtection(dt, state.isOnGround());
        swimming.updateWaterState();
        swimming.applyAntiFloatingPreIntegration(flight.isFlying(),
                jumpHandler.getLastNormalJumpTime(), jumpHandler.getNormalJumpGracePeriod());
        swimming.applyWaterFlow(flight.isFlying());

        movement.applyGravity();
        Vector3f posBeforeIntegrate = state.getPosition();
        float prevX = posBeforeIntegrate.x;
        float prevZ = posBeforeIntegrate.z;
        boolean wasOnGround = state.isOnGround();
        boolean wasSprinting = stamina.isSprinting();
        movement.integrateAndCollide();
        float dx = posBeforeIntegrate.x - prevX;
        float dz = posBeforeIntegrate.z - prevZ;
        float horizDist = (float) Math.sqrt(dx * dx + dz * dz);
        if (horizDist > 0f) {
            stats.addTotalDistance(horizDist);
            if (wasOnGround && wasSprinting) {
                stats.addDistanceSprinted(horizDist);
            } else if (wasOnGround) {
                stats.addDistanceWalked(horizDist);
            } else {
                stats.addDistanceInAir(horizDist);
            }
        }
        if (!wasOnGround) {
            stats.addTimeInAir(dt);
        }
        if (spectator.isActive()) {
            state.setOnGround(false);
        } else {
            groundChecker.check();
        }
        movement.applyDamping();

        Vector3f p = state.getPosition();
        if (perspective == Perspective.THIRD_PERSON) {
            // Pull camera back behind and slightly above the player, but stop short of
            // any solid terrain in the way so it never clips through walls/cliffs.
            Vector3f pivot = new Vector3f(p.x, p.y + CAMERA_EYE_OFFSET, p.z);
            Vector3f offset = new Vector3f(camera.getFront()).mul(-4.0f).add(0f, 0.5f, 0f);
            float desired = offset.length();
            Vector3f dir = offset.normalize(new Vector3f());
            float hit = raycastEngine.distanceToFirstSolid(pivot, dir, desired);
            float dist = (hit == Float.MAX_VALUE) ? desired : Math.max(0.5f, hit - 0.3f);
            camera.setPosition(
                    pivot.x + dir.x * dist,
                    pivot.y + dir.y * dist,
                    pivot.z + dir.z * dist);
        } else {
            camera.setPosition(p.x, p.y + CAMERA_EYE_OFFSET, p.z);
        }

        berserkerAbilities.update(dt, this);
        rangerAbilities.update(dt, this);
        arcanistAbilities.update(dt, this);
        illusionistAbilities.update(dt, this);
        rogueAbilities.update(dt, this);
        dodge.update(dt, this);
        stealth.update(dt, this);
        // Any health decrease (combat, fall, drowning) cancels stealth entry / breaks stealth.
        float currentHealth = health.getHealth();
        if (currentHealth < lastHealthForStealth - 0.001f) {
            stealth.onDamageTaken(this);
        }
        lastHealthForStealth = currentHealth;
        RageTier rageTier = berserkerAbilities.getRage().getTier();
        attack.setAnimationSpeedMultiplier(rageTier.atLeast(RageTier.T2)
            ? 1f + RAGE_T2_ATTACK_SPEED_BONUS
            : 1f);
        attack.update(dt);
        bow.update(dt);
        stamina.update(dt);
        mana.update(dt);
        blockBreaker.update();

        com.stonebreak.audio.PlayerSounds playerSounds = Game.getPlayerSounds();
        if (playerSounds != null) {
            playerSounds.updateWalkingSounds(p, state.getVelocity(), state.isOnGround(), state.isPhysicallyInWater());
        }
        if (Game.getWorld() != null) {
            Game.getSoundSystem().setListenerFromCamera(p, camera.getFront(), camera.getUp());
        }

        fallDamage.update(flight.isFlying());
        deathHandler.processDeathIfNeeded();

        // Advance body animation clocks (used by third-person renderer).
        bodyAnimationTime += dt;
        if (attack.isAttacking()) attackEventTime += dt; else attackEventTime = 0f;
        if (!state.isOnGround()) jumpEventTime += dt; else jumpEventTime = 0f;
        // Attack overlay envelope: attack plays on top of the locomotion clip,
        // masked to the parts the attack clip owns, with fade in/out.
        attackOverlay.update(dt, attack.isAttacking());

        // Third-person body faces movement / look direction; the camera only
        // supplies the look yaw, converted from its front vector into model space.
        Vector3f front = camera.getFront();
        float lookModelYaw = PlayerBodyOrientation.modelYawFromDirection(front.x, front.z);
        bodyOrientation.update(dt, state.getVelocity(), lookModelYaw);
    }

    /**
     * True when the chunk under the player is resident AND rendered (filled with streamed data
     * + meshed). On the client render world an unfilled placeholder reports false, so physics
     * holds the player until real terrain arrives rather than dropping them through it.
     */
    private boolean isGroundChunkReady() {
        World w = Game.getWorld();
        if (w == null) {
            return false;
        }
        Vector3f p = state.getPosition();
        int cx = Math.floorDiv((int) Math.floor(p.x), 16);
        int cz = Math.floorDiv((int) Math.floor(p.z), 16);
        return w.isChunkRenderableAt(cx, cz);
    }

    public void processMovement(boolean forward, boolean backward, boolean left, boolean right,
                                boolean jump, boolean shift) {
        boolean moving = forward || backward || left || right;
        boolean sprinting = shift && moving && !flight.isFlying()
                            && !state.isPhysicallyInWater()
                            && stamina.hasStamina()
                            && !stealth.isSprintBlocked(); // cannot sprint while stealthed
        stamina.setSprinting(sprinting);
        jumpHandler.setCanDoubleJump(characterStats.hasFeat("double_jump"));
        float speedMultiplier = rangerAbilities.getSpeedMultiplier(this,
                computeIntendedMoveDirection(forward, backward, left, right));
        speedMultiplier *= stealth.getMovementMultiplier(this); // stealth movement penalty
        movement.processMovement(forward, backward, left, right, jump, shift, sprinting, speedMultiplier);
    }

    /**
     * Horizontal direction the WASD input is asking for (same camera math as
     * {@link MovementController}), normalized, or the zero vector when no movement
     * keys are held or the inputs cancel out.
     */
    private Vector3f computeIntendedMoveDirection(boolean forward, boolean backward,
                                                  boolean left, boolean right) {
        Vector3f front = camera.getFront();
        Vector3f rightVec = camera.getRight();
        Vector3f frontDirection = new Vector3f(front.x, 0, front.z);
        Vector3f rightDirection = new Vector3f(rightVec.x, 0, rightVec.z);
        Vector3f intended = new Vector3f();
        if (forward) intended.add(frontDirection);
        if (backward) intended.sub(frontDirection);
        if (right) intended.add(rightDirection);
        if (left) intended.sub(rightDirection);
        if (intended.lengthSquared() > 0.0001f) {
            intended.normalize();
        } else {
            intended.set(0f, 0f, 0f);
        }
        return intended;
    }

    public void updateDerivedStats() {
        health.applyNewMaxHealth(characterStats.computeMaxHealth());
        stamina.setMaxStamina(characterStats.computeMaxStamina());
        mana.setMaxMana(characterStats.computeMaxMana());
        mana.setRegenRate(characterStats.computeManaRegen());
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

    // Fishing
    public com.stonebreak.mobs.entities.FishingBobber getActiveBobber() { return activeBobber; }
    public void setActiveBobber(com.stonebreak.mobs.entities.FishingBobber b) { activeBobber = b; }

    // RPG
    public CharacterStats getCharacterStats() { return characterStats; }

    // Statistics
    public PlayerStats getStats() { return stats; }

    // Entity glossary
    public EntityDiscoveries getEntityDiscoveries() { return discoveries; }
    public EntitySightingTracker getEntitySightingTracker() { return sightingTracker; }

    // Stamina / mana
    public float getStamina()    { return stamina.getStamina(); }
    public float getMaxStamina() { return stamina.getMaxStamina(); }
    public boolean canAffordStamina(float amount) { return stamina.canAfford(amount); }
    public boolean consumeStamina(float amount)   { return stamina.consume(amount); }
    public float getMana()       { return mana.getMana(); }
    public float getMaxMana()    { return mana.getMaxMana(); }
    public ManaController getManaController() { return mana; }

    // Health / death
    public float getHealth() { return health.getHealth(); }
    public float getMaxHealth() { return health.getMaxHealth(); }
    public boolean isDead() { return health.isDead(); }
    public int getHearts() { return health.getHearts(); }
    public void setHealth(float h) { health.setHealth(h); }
    public void damage(float amount) {
        if (dodge.isInvincible()) return;   // dodge i-frames negate combat damage
        berserkerAbilities.getRage().onHitReceived();
        health.damage(amount);
        // Stealth break on damage is handled centrally in update() by watching health decrease,
        // so environmental sources (fall, drowning) that call health.damage() directly count too.
    }
    public void heal(float amount) { health.heal(amount); }
    public void respawn() { deathHandler.respawn(); }

    // Attack animation
    public boolean isAttacking() { return attack.isAttacking(); }
    public void startAttackAnimation() { attack.startAttackAnimation(); }
    public float getAttackAnimationProgress() { return attack.getAnimationProgress(); }
    public float getRawAttackAnimationProgress() { return attack.getRawAnimationProgress(); }

    // Bow draw
    public BowController getBowController() { return bow; }
    public boolean isDrawingBow() { return bow.isDrawing(); }
    public float getBowDrawProgress() { return bow.getDrawProgress(); }
    public String getBowSboState() { return bow.getBowSboState(); }

    // Flight
    public boolean isFlying() { return flight.isFlying(); }
    public void setFlying(boolean flying) { flight.setFlying(flying); }
    public boolean isFlightEnabled() { return flight.isFlightEnabled(); }
    public void setFlightEnabled(boolean enabled) { flight.setFlightEnabled(enabled); }

    // Spectator
    public boolean isSpectator() { return spectator.isActive(); }
    public void setSpectator(boolean active) { spectator.setActive(active); }
    public boolean isPlayerInsideSolidBlock() { return collisionHandler.isPlayerInsideSolidBlock(); }

    // Water
    public boolean isInWater() { return swimming.isInWater(); }

    public RaycastEngine getRaycastEngine() { return raycastEngine; }

    // Third-person / body animation
    public Perspective getPerspective() { return perspective; }

    public void togglePerspective() {
        perspective = (perspective == Perspective.FIRST_PERSON)
                ? Perspective.THIRD_PERSON
                : Perspective.FIRST_PERSON;
    }

    public boolean isThirdPerson() { return perspective == Perspective.THIRD_PERSON; }

    /**
     * Lower-body facing in the player model's facing space (degrees); see
     * {@link PlayerBodyOrientation#modelYawFromDirection}. Tracks movement / look
     * direction, smoothed; the base yaw for the third-person body model.
     */
    public float getBodyYaw() { return bodyOrientation.getBodyYaw(); }

    /** Third-person head yaw relative to the body, clamped to its swivel range. */
    public float getThirdPersonHeadYaw() {
        Vector3f front = camera.getFront();
        return bodyOrientation.getHeadYaw(PlayerBodyOrientation.modelYawFromDirection(front.x, front.z));
    }

    /**
     * Third-person head pitch, clamped so the head never over-rotates. The
     * camera pitch is passed through directly: the third-person body's 180°
     * facing flip (see {@link PlayerBodyOrientation#modelYawFromDirection}) puts
     * a {@code rotateY(180°)} in the model's base transform, which inverts the
     * head bone's local X axis — so {@code rotateX(cameraPitch)} already tilts
     * the head the same way the camera looks (up→up, down→down).
     */
    public float getThirdPersonHeadPitch() { return bodyOrientation.getHeadPitch(camera.getPitch()); }

    /** Continuously advancing animation clock for the body model (Walking). */
    public float getBodyAnimationTime() { return bodyAnimationTime; }

    /**
     * Animation time to feed for one-shot BASE clips (Jumping). Attack is no
     * longer part of the base state — it plays as an overlay with its own
     * clock (see {@link #getAttackOverlay()}).
     */
    public float getBodyEventTime() {
        if (!state.isOnGround()) return jumpEventTime;
        return bodyAnimationTime;
    }

    /**
     * The base locomotion state: JUMPING &gt; WALKING &gt; IDLE. Attacking is
     * NOT considered — it renders as an overlay on top of this, so the legs
     * keep walking mid-swing.
     */
    public com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState getBaseMovementState() {
        if (!state.isOnGround()) return com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.JUMPING;
        Vector3f vel = state.getVelocity();
        float horizSpeed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (horizSpeed > WALK_SPEED_THRESHOLD) return com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.WALKING;
        return com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.IDLE;
    }

    /**
     * Single collapsed state, kept for callers that predate animation mixing
     * (attack still wins here).
     */
    public com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState getMovementState() {
        if (attack.isAttacking()) return com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.ATTACKING;
        return getBaseMovementState();
    }

    /** Envelope tracker for the attack overlay animation (time + fade weight). */
    public com.stonebreak.mobs.sbe.OverlayAnimState getAttackOverlay() { return attackOverlay; }

    /** Returns the melee damage for the player's currently held item (1.0 for bare fist). */
    public float getAttackDamage() {
        ItemStack held = inventory.getSelectedHotbarSlot();
        if (!held.isEmpty() && held.getItem() instanceof ItemType itemType) {
            return itemType.getDamage();
        }
        return 1.0f;
    }

    /** Multiplier applied to melee damage from the Berserker's Rage tier (T1+ grants increased damage). */
    public float getMeleeDamageMultiplier() {
        RageTier tier = berserkerAbilities.getRage().getTier();
        return tier.atLeast(RageTier.T1)
            ? 1f + RAGE_T1_DAMAGE_BONUS
            : 1f;
    }

    /**
     * Resolves a melee hit on {@code target}: applies Rage-scaled damage, grants Rage for
     * the hit dealt, and — at Rage T3 — heals the player via lifesteal. Centralizes melee
     * combat resolution so Berserker bonuses apply uniformly regardless of caller.
     */
    public void attackEntity(LivingEntity target) {
        float damageDealt = getAttackDamage() * getMeleeDamageMultiplier();

        // Stealth opener: striking an unaware enemy leaves it flat-footed (so the crit roll below,
        // and any follow-up hit within the window, gains bonus crit chance).
        AwarenessController awareness = target.getAwareness();
        if (awareness != null
                && awareness.getState() == AwarenessController.AwarenessState.UNAWARE) {
            target.applyStatusEffect(StatusEffectType.FLAT_FOOTED,
                    stealth.getFlatFootedDuration(this), 0f);
        }

        // Crit-chance roll. Base chance is 0 today; a flat-footed target adds the class crit bonus
        // (Rogue = 1.0 → guaranteed). On a crit, scale by the generic crit multiplier, then let the
        // Rogue's Momentum (if any) amplify it further and apply its tier debuff.
        float critChance = target.hasStatusEffect(StatusEffectType.FLAT_FOOTED)
                ? stealth.getFlatFootedCritBonus(this) : 0f;
        if (critChance > 0f && critRandom.nextFloat() < critChance) {
            damageDealt *= PlayerConstants.PLAYER_CRIT_MULTIPLIER;
            damageDealt *= rogueAbilities.onCritLanded(this, target);
        }

        target.damage(damageDealt, LivingEntity.DamageSource.PLAYER);
        berserkerAbilities.getRage().onMeleeHitDealt();
        rangerAbilities.onPlayerMeleeHit(this, target);
        stealth.onAttack(this); // attacking breaks stealth instantly

        if (berserkerAbilities.getRage().getTier().atLeast(RageTier.T3)) {
            heal(damageDealt * RAGE_T3_LIFESTEAL_PCT);
        }
    }

    // Berserker
    public BerserkerAbilityController getBerserkerAbilities() { return berserkerAbilities; }

    // Ranger
    public RangerAbilityController getRangerAbilities() { return rangerAbilities; }

    // Arcanist
    public ArcanistAbilityController getArcanistAbilities() { return arcanistAbilities; }

    // Illusionist
    public IllusionistAbilityController getIllusionistAbilities() { return illusionistAbilities; }

    // Rogue
    public RogueAbilityController getRogueAbilities() { return rogueAbilities; }
    public boolean tryCastMirroredDeceit() { return illusionistAbilities.tryCastMirroredDeceit(this); }
    public boolean tryCastFracture() { return illusionistAbilities.tryCastFracture(this); }

    /** True while any class ability is driving the player and movement input should be suppressed. */
    public boolean isAbilityMovementLocked() {
        return berserkerAbilities.isMovementLocked() || rangerAbilities.isMovementLocked()
            || dodge.isMovementLocked();
    }

    // Dodge (universal)
    public DodgeController getDodge() { return dodge; }

    /**
     * Triggers a dodge dash in the direction the WASD input is currently asking for (or backward
     * when no movement key is held). Resolves the intended direction from the same camera-relative
     * math as movement so the dash follows live input, not residual momentum.
     */
    public boolean tryDodge(boolean forward, boolean backward, boolean left, boolean right) {
        return dodge.tryDodge(this, computeIntendedMoveDirection(forward, backward, left, right));
    }

    /** Noise radius (blocks) spiked by a recent dodge; 0 when not spiked. Read by the stealth system. */
    public float getDodgeNoiseRadius() { return dodge.getCurrentNoiseRadius(); }

    // Stealth (universal)
    public StealthController getStealth() { return stealth; }

    /** Current player noise radius (blocks) for enemy sound detection (movement state + dodge spike). */
    public float getCurrentNoiseRadius() { return stealth.getNoiseRadius(this); }

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
        // Quarry mark, trap, and ability state reference entities from the old world
        rangerAbilities.reset();
        // Resonance is a combat-only resource; spawned zones/projectiles are gone with the old world
        arcanistAbilities.reset();
        // Doubt tracks entities from the old world; decoys are gone with it
        illusionistAbilities.reset();
        // Momentum and ability cooldowns reset; caltrop entities are gone with the old world
        rogueAbilities.reset();
    }
}
