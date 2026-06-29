package com.stonebreak.player.combat.ranger;

import static com.stonebreak.player.PlayerConstants.RANGER_EXPOSED_DURATION;
import static com.stonebreak.player.PlayerConstants.RANGER_EXPOSED_MAGNITUDE;
import static com.stonebreak.player.PlayerConstants.RANGER_SNARE_ARM_TIME;
import static com.stonebreak.player.PlayerConstants.RANGER_SNARE_COOLDOWN;
import static com.stonebreak.player.PlayerConstants.RANGER_SNARE_PLACE_RANGE;
import static com.stonebreak.player.PlayerConstants.RANGER_SNARE_RADIUS;
import static com.stonebreak.player.PlayerConstants.RANGER_SNARE_ROOT_DURATION;
import static com.stonebreak.player.PlayerConstants.RANGER_SNARE_ROOT_EXTENSION;
import static com.stonebreak.player.PlayerConstants.RANGER_SNARE_STACKS_ON_TRIGGER;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.QuarryController;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * Ranger active: places a ground trap at the targeted block. The trap arms after a brief
 * delay (no point-blank instant root), persists until triggered or replaced (only one at
 * a time), and on trigger roots the victim and instantly applies two Study stacks. If the
 * victim was already a studied Quarry, the root lasts longer and the victim is Exposed
 * (increased damage taken from all sources). State machine: IDLE -&gt; COOLDOWN; the trap
 * itself is independent persistent state.
 */
public class SnareAbility {

    private enum State { IDLE, COOLDOWN }

    private State state = State.IDLE;
    private float cooldownRemaining;

    private boolean trapActive;
    private float armRemaining;
    private final Vector3f trapPosition = new Vector3f();

    public boolean isActive() { return state != State.IDLE; }
    public boolean isOnCooldown() { return state == State.COOLDOWN; }
    public float getCooldownRemaining() { return state == State.COOLDOWN ? cooldownRemaining : 0f; }

    public boolean hasActiveTrap() { return trapActive; }
    public boolean isTrapArmed() { return trapActive && armRemaining <= 0f; }
    public Vector3f getTrapPosition() { return new Vector3f(trapPosition); }

    /** 0..1 arm telegraph progress; 1 once the trap is live. */
    public float getArmProgress() {
        if (!trapActive) return 0f;
        return Math.min(1f, (RANGER_SNARE_ARM_TIME - armRemaining) / RANGER_SNARE_ARM_TIME);
    }

    /**
     * Attempts to place a trap on top of the targeted block (extended placement range).
     * Fails without consuming the cooldown when no block is in range. Placing a new trap
     * replaces any existing one.
     */
    public boolean tryActivate(Player player) {
        if (state != State.IDLE) return false;

        Vector3i blockHit = player.getRaycastEngine().raycast(RANGER_SNARE_PLACE_RANGE);
        if (blockHit == null) return false;

        trapPosition.set(blockHit.x + 0.5f, blockHit.y + 1f, blockHit.z + 0.5f);
        trapActive = true;
        armRemaining = RANGER_SNARE_ARM_TIME;
        state = State.COOLDOWN;
        cooldownRemaining = RANGER_SNARE_COOLDOWN;
        return true;
    }

    public void update(float deltaTime, QuarryController quarry) {
        if (state == State.COOLDOWN) {
            cooldownRemaining -= deltaTime;
            if (cooldownRemaining <= 0f) {
                state = State.IDLE;
            }
        }
        if (!trapActive) return;

        if (armRemaining > 0f) {
            armRemaining -= deltaTime;
            return;
        }
        checkTrigger(quarry);
    }

    /** Clears the placed trap and cooldown (world reload). */
    public void reset() {
        state = State.IDLE;
        cooldownRemaining = 0f;
        trapActive = false;
        armRemaining = 0f;
    }

    private void checkTrigger(QuarryController quarry) {
        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;

        for (LivingEntity entity : entityManager.getLivingEntities()) {
            if (!entity.isAlive()) continue;
            if (entity.getPosition().distance(trapPosition) > RANGER_SNARE_RADIUS) continue;

            boolean wasStudiedQuarry = entity == quarry.getQuarry() && quarry.getStudyStacks() > 0;
            quarry.addStacks(entity, RANGER_SNARE_STACKS_ON_TRIGGER);

            float rootDuration = wasStudiedQuarry
                ? RANGER_SNARE_ROOT_DURATION + RANGER_SNARE_ROOT_EXTENSION
                : RANGER_SNARE_ROOT_DURATION;
            entity.applyStatusEffect(StatusEffectType.ROOT, rootDuration, 0f);

            if (wasStudiedQuarry) {
                entity.applyStatusEffect(StatusEffectType.EXPOSED,
                    RANGER_EXPOSED_DURATION, RANGER_EXPOSED_MAGNITUDE);
            }

            trapActive = false;
            return;
        }
    }
}
