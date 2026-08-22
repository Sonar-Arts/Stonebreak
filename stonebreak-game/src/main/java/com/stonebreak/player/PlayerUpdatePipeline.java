package com.stonebreak.player;

import com.stonebreak.core.Game;
import com.stonebreak.player.combat.RageTier;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import static com.stonebreak.player.PlayerConstants.CAMERA_EYE_OFFSET;
import static com.stonebreak.player.PlayerConstants.RAGE_T2_ATTACK_SPEED_BONUS;

/**
 * Per-tick orchestration of the player's controllers. Sequences the death check,
 * chunk-readiness hold, water state and effects, gravity/integration/ground check,
 * camera placement, class abilities, combat timers, audio, fall damage and body
 * animation in a fixed order. {@link Player} delegates {@link Player#update()} here
 * and keeps construction, state and the facade accessors.
 */
final class PlayerUpdatePipeline {

    private final Player player;
    private final PlayerControllers c;
    private final PhysicsState state;
    private final Camera camera;
    private final PlayerStats stats;
    private final PlayerWaterEffects waterEffects;
    private final PlayerBodyAnimation bodyAnimation;
    private float lastHealthForStealth; // tracks health between frames to detect any damage taken

    PlayerUpdatePipeline(Player player, PlayerControllers controllers, PhysicsState state, Camera camera,
                         PlayerStats stats, PlayerWaterEffects waterEffects, PlayerBodyAnimation bodyAnimation) {
        this.player = player;
        this.c = controllers;
        this.state = state;
        this.camera = camera;
        this.stats = stats;
        this.waterEffects = waterEffects;
        this.bodyAnimation = bodyAnimation;
    }

    void update() {
        if (c.health.isDead()) {
            c.deathHandler.processDeathIfNeeded();
            return;
        }

        float dt = Game.getDeltaTime();

        // Don't fall through terrain that hasn't streamed in yet (async client render world). If
        // the chunk under us isn't rendered — an empty placeholder, or not arrived — hold
        // position instead of dropping into the void. Flight/spectator move freely (no fall).
        if (!c.flight.isFlying() && !c.spectator.isActive() && !isGroundChunkReady()) {
            state.getVelocity().set(0f, 0f, 0f);
            Vector3f hp = state.getPosition();
            camera.setPosition(hp.x, hp.y + CAMERA_EYE_OFFSET, hp.z);
            return;
        }

        c.health.updateSpawnProtection(dt, state.isOnGround());
        c.swimming.updateWaterState();
        Game.getSoundSystem().setEnvironmentGain(
                c.swimming.isInWater() ? PlayerConstants.UNDERWATER_AUDIO_DUCK_GAIN : 1.0f);
        waterEffects.update(dt, state, c.swimming.isInWater());
        c.swimming.applyAntiFloatingPreIntegration(c.flight.isFlying(),
                c.jumpHandler.getLastNormalJumpTime(), c.jumpHandler.getNormalJumpGracePeriod());
        c.swimming.applyWaterFlow(c.flight.isFlying());

        c.movement.applyGravity();
        Vector3f posBeforeIntegrate = state.getPosition();
        float prevX = posBeforeIntegrate.x;
        float prevZ = posBeforeIntegrate.z;
        boolean wasOnGround = state.isOnGround();
        boolean wasSprinting = c.stamina.isSprinting();
        c.movement.integrateAndCollide();
        recordDistance(dt, posBeforeIntegrate.x - prevX, posBeforeIntegrate.z - prevZ, wasOnGround, wasSprinting);
        if (c.spectator.isActive()) {
            state.setOnGround(false);
        } else {
            c.groundChecker.check();
        }
        c.movement.applyDamping();

        Vector3f p = state.getPosition();
        placeCamera(p);

        c.berserkerAbilities.update(dt, player);
        c.rangerAbilities.update(dt, player);
        c.arcanistAbilities.update(dt, player);
        c.illusionistAbilities.update(dt, player);
        c.rogueAbilities.update(dt, player);
        c.dodge.update(dt, player);
        c.stealth.update(dt, player);
        // Any health decrease (combat, fall, drowning) cancels stealth entry / breaks stealth.
        float currentHealth = c.health.getHealth();
        if (currentHealth < lastHealthForStealth - 0.001f) {
            c.stealth.onDamageTaken(player);
        }
        lastHealthForStealth = currentHealth;
        RageTier rageTier = c.berserkerAbilities.getRage().getTier();
        c.attack.setAnimationSpeedMultiplier(rageTier.atLeast(RageTier.T2)
            ? 1f + RAGE_T2_ATTACK_SPEED_BONUS
            : 1f);
        c.attack.update(dt);
        c.bow.update(dt);
        c.stamina.update(dt);
        c.mana.update(dt);
        c.blockBreaker.update();

        com.stonebreak.audio.PlayerSounds playerSounds = Game.getPlayerSounds();
        if (playerSounds != null) {
            playerSounds.updateWalkingSounds(p, state.getVelocity(), state.isOnGround(), state.isPhysicallyInWater());
        }
        if (Game.getWorld() != null) {
            Game.getSoundSystem().setListenerFromCamera(p, camera.getFront(), camera.getUp());
        }

        c.fallDamage.update(c.flight.isFlying());
        c.deathHandler.processDeathIfNeeded();

        bodyAnimation.update(dt, c.attack.isAttacking(), state.isOnGround(), state.getVelocity(), camera.getFront());
    }

    /** Accumulates horizontal travel (walked / sprinted / airborne) and airtime into the statistics. */
    private void recordDistance(float dt, float dx, float dz, boolean wasOnGround, boolean wasSprinting) {
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
    }

    /** Eye-height camera in first person; terrain-clamped orbit camera in third person. */
    private void placeCamera(Vector3f p) {
        if (player.getPerspective() == Player.Perspective.THIRD_PERSON) {
            // Pull camera back behind and slightly above the player, but stop short of
            // any solid terrain in the way so it never clips through walls/cliffs.
            Vector3f pivot = new Vector3f(p.x, p.y + CAMERA_EYE_OFFSET, p.z);
            Vector3f offset = new Vector3f(camera.getFront()).mul(-4.0f).add(0f, 0.5f, 0f);
            float desired = offset.length();
            Vector3f dir = offset.normalize(new Vector3f());
            float hit = c.raycastEngine.distanceToFirstSolid(pivot, dir, desired);
            float dist = (hit == Float.MAX_VALUE) ? desired : Math.max(0.5f, hit - 0.3f);
            camera.setPosition(
                    pivot.x + dir.x * dist,
                    pivot.y + dir.y * dist,
                    pivot.z + dir.z * dist);
        } else {
            camera.setPosition(p.x, p.y + CAMERA_EYE_OFFSET, p.z);
        }
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
}
