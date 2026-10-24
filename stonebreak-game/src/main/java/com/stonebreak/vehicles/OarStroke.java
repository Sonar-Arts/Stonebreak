package com.stonebreak.vehicles;

import org.joml.Vector3f;

/**
 * Client-side rowing animation for a dinghy's two oars, driven by the replicated
 * {@link OarInput}. Each oar runs its own stroke cycle at a rate proportional to how hard it is
 * pulling, so a gentle turn visibly rows the inner oar slower and a hard turn rows one oar only.
 *
 * <p>One cycle, by phase {@code φ} (pulling advances φ, backing water runs it backwards):
 * <ul>
 *   <li>sweep (about the oarlock's vertical axis) = {@code SWEEP · cos φ} — catch at the bow
 *       (φ = 0), finish at the stern (φ = π);</li>
 *   <li>dip (about the oar's long-axis normal) = {@code DIP · sin φ} — the blade is buried on the
 *       drive (0..π) and feathered clear on the recovery (π..2π).</li>
 * </ul>
 * With no input the stroke fades out to the model's rest pose instead of snapping.
 */
public final class OarStroke {

    /** Half the fore-aft swing of the blade, degrees. */
    static final float SWEEP_DEG = 30f;
    /** How far the blade dips on the drive / lifts on the recovery, degrees. */
    static final float DIP_DEG = 10f;
    /** Stroke rate at full power, radians per second (~1.1 s per stroke). */
    static final float ROW_RATE = (float) (2.0 * Math.PI / 1.1);
    /** How fast a stroke fades in/out when an oar starts/stops, per second. */
    static final float FADE_RATE = 4f;
    /** How fast equal-power oars pull back into step, per second. */
    static final float SYNC_RATE = 3f;

    public static final String LEFT_OAR_PART = "oar_l";
    public static final String RIGHT_OAR_PART = "oar_r";

    private final Oar left = new Oar(-1f);
    private final Oar right = new Oar(1f);

    public void update(OarInput input, float dt) {
        left.update(input.left(), dt);
        right.update(input.right(), dt);
        // Straight rowing: two rowers' worth of oars fall back into step after a turn.
        if (input.left() != 0f && input.left() == input.right()) {
            float diff = wrap(right.phase - left.phase);
            float pull = Math.min(1f, SYNC_RATE * dt) * 0.5f;
            left.phase += diff * pull;
            right.phase -= diff * pull;
        }
    }

    /** Extra Euler degrees for {@code partName}, or null when it is not an oar. */
    public Vector3f extraRotationDeg(String partName) {
        if (LEFT_OAR_PART.equals(partName)) {
            return left.rotation();
        }
        if (RIGHT_OAR_PART.equals(partName)) {
            return right.rotation();
        }
        return null;
    }

    float leftPhase() { return left.phase; }
    float rightPhase() { return right.phase; }
    float leftAmount() { return left.amount; }
    float rightAmount() { return right.amount; }

    private static float wrap(float a) {
        double twoPi = 2.0 * Math.PI;
        double w = (a + Math.PI) % twoPi;
        if (w < 0) {
            w += twoPi;
        }
        return (float) (w - Math.PI);
    }

    private static final class Oar {
        /** +1 for the right oar (extends along +X), -1 for the mirrored left oar. */
        private final float side;
        private float phase;
        private float amount;
        private final Vector3f rotation = new Vector3f();

        Oar(float side) {
            this.side = side;
        }

        void update(float power, float dt) {
            phase = wrap(phase + power * ROW_RATE * dt);
            float target = power != 0f ? 1f : 0f;
            float step = FADE_RATE * dt;
            amount = amount < target ? Math.min(target, amount + step) : Math.max(target, amount - step);
        }

        Vector3f rotation() {
            if (amount <= 0f) {
                return null;
            }
            // A +Y turn swings a +X oar's blade toward the bow (-Z); a -Z turn dips it.
            float sweep = SWEEP_DEG * (float) Math.cos(phase) * amount;
            float dip = DIP_DEG * (float) Math.sin(phase) * amount;
            return rotation.set(0f, side * sweep, -side * dip);
        }
    }
}
