package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.perception.Loudness;
import com.stonebreak.mobs.entities.ai.perception.NoiseBus;
import com.stonebreak.mobs.entities.combat.WeaponProfile;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * Shoots a bow at its {@link AttackTarget} — the player unless told otherwise — in range and in sight: plant feet, draw while tracking the target,
 * loose at the end of the draw clip, then play the follow-through. A draw is abandoned if the
 * player gets out of range before it finishes.
 */
public final class RangedAttackBehavior implements Behavior {

    private static final float TURN_SPEED = 360.0f;
    /** Bow height on the mob, as a fraction of its height. */
    private static final float BOW_HEIGHT_FRACTION = 0.75f;
    /** Net downward pull on a flying arrow (engine gravity less the arrow's lift); see Arrow. */
    private static final float ARROW_NET_GRAVITY = 10.0f;

    private enum Phase { DRAW, RELEASE, DONE }

    private final WeaponProfile.Ranged weapon;
    private final ArrowLauncher launcher;
    private final AttackTarget target;

    private final Vector3d eye = new Vector3d();
    private final Vector3f aim = new Vector3f();

    private Phase phase = Phase.DONE;
    private float elapsed;
    /** Entity age at which the next draw may start. */
    private float readyAt;

    public RangedAttackBehavior(WeaponProfile.Ranged weapon, ArrowLauncher launcher) {
        this(weapon, launcher, AttackTarget.PLAYER);
    }

    public RangedAttackBehavior(WeaponProfile.Ranged weapon, ArrowLauncher launcher, AttackTarget target) {
        this.weapon = weapon;
        this.launcher = launcher;
        this.target = target;
    }

    @Override
    public int priority() {
        return MeleeAttackBehavior.PRIORITY;
    }

    @Override
    public EnumSet<Flag> flags() {
        return EnumSet.of(Flag.MOVE);
    }

    @Override
    public boolean canStart(AiContext context) {
        if (context.entity().getAge() < readyAt || !target.engaged(context)) {
            return false;
        }
        float distance = context.distanceTo(target.position(context));
        return distance >= weapon.minRange() && distance <= weapon.maxRange()
                && target.canSee(context, eyeOf(context.entity()));
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return phase != Phase.DONE;
    }

    @Override
    public void start(AiContext context) {
        phase = Phase.DRAW;
        elapsed = 0.0f;
        context.nav().stop();
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        elapsed += deltaTime;
        context.steering().stopMoving();
        switch (phase) {
            case DRAW -> {
                context.facePoint(target.position(context), TURN_SPEED);
                if (context.distanceTo(target.position(context)) > weapon.maxRange()) {
                    phase = Phase.DONE; // they got away: lower the bow
                } else if (elapsed >= weapon.drawSeconds()) {
                    loose(context);
                    phase = Phase.RELEASE;
                    elapsed = 0.0f;
                }
            }
            case RELEASE -> {
                if (elapsed >= weapon.releaseSeconds()) {
                    phase = Phase.DONE;
                }
            }
            case DONE -> { }
        }
    }

    @Override
    public void stop(AiContext context) {
        phase = Phase.DONE;
        readyAt = context.entity().getAge() + weapon.cooldown();
    }

    @Override
    public MobBehaviorState animationState() {
        return phase == Phase.RELEASE ? MobBehaviorState.RELEASE_BOW : MobBehaviorState.DRAW_BOW;
    }

    @Override
    public String debugName() {
        return "Ranged";
    }

    /** Fires at the target's aim point, lifted to cancel the drop over the flight, plus some wobble. */
    private void loose(AiContext context) {
        Vector3d victim = target.position(context);
        if (victim == null) {
            return;
        }
        Vector3d origin = eyeOf(context.entity());
        // Aim as an offset from the bow (a small float vector wherever the fight is).
        aim.set((float) (victim.x - origin.x), (float) (victim.y + target.aimHeight(context) - origin.y),
                (float) (victim.z - origin.z));
        float horizontal = (float) Math.hypot(aim.x, aim.z);
        float flightSeconds = horizontal / weapon.arrowSpeed();
        aim.y += 0.5f * ARROW_NET_GRAVITY * flightSeconds * flightSeconds;
        aim.normalize();
        aim.add((float) context.random().nextGaussian() * weapon.spread(),
                (float) context.random().nextGaussian() * weapon.spread(),
                (float) context.random().nextGaussian() * weapon.spread()).normalize(weapon.arrowSpeed());
        launcher.launch(context.entity(), new Vector3d(origin), new Vector3f(aim), weapon.damage());
        if (target.noisy(context)) {
            NoiseBus.emitIn(context.world(), context.entity().getPosition(), Loudness.RANGED_ATTACK, context.entity());
        }
    }

    private Vector3d eyeOf(LivingEntity entity) {
        return eye.set(entity.getPosition()).add(0, entity.getHeight() * BOW_HEIGHT_FRACTION, 0);
    }
}
