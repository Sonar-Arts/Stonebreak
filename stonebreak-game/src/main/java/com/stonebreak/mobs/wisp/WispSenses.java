package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.mobs.entities.ai.perception.PlayerGaze;
import org.joml.Vector3f;

/**
 * One tick's read of the Wisp's victim, taken once before its behaviours run so they all act on
 * the same picture: where the victim is and looks, whether they could see the Wisp, and whether
 * they are looking right at it. Also where the victim lock comes from — the nearest player in the
 * Shade, kept until they are gone.
 */
final class WispSenses {

    /** How far a Wisp looks for a victim, and how far a victim can make it out in the fog. */
    static final float VICTIM_SEARCH_RADIUS = 96f;
    static final float SIGHT_RANGE = 48f;
    /** Inside this, even a veiled Wisp's shimmer gives it away to a watching player. */
    static final float SHIMMER_RANGE = 4f;
    /** Height of the Wisp's eyes above its feet (the model, scaled). */
    static final float HEAD_HEIGHT = 1.8f;
    static final float CHEST_HEIGHT = 1.0f;

    private final LivingEntity wisp;
    private final PlayerLocator players;
    private final ShadeColumns shade;

    private int victimId = PlayerLocator.NEAREST;
    private boolean hasVictim;
    private boolean victimChosen;
    private final Vector3f victimFeet = new Vector3f();
    private final Vector3f victimEye = new Vector3f();
    private final Vector3f victimLook = new Vector3f();
    private boolean hasLook;
    private boolean victimInShade;
    private float distance = Float.MAX_VALUE;
    private boolean inView;
    private boolean watched;

    private final Vector3f head = new Vector3f();
    private final Vector3f chest = new Vector3f();
    private final Vector3f scratch = new Vector3f();

    WispSenses(LivingEntity wisp, PlayerLocator players, ShadeColumns shade) {
        this.wisp = wisp;
        this.players = players;
        this.shade = shade;
    }

    /**
     * Takes this tick's read. Returns true when the victim changed (picked for the first time or
     * replaced after the last one was lost), so the caller can forget what it knew of the old one.
     */
    boolean refresh(boolean veiled, boolean eyesDark) {
        Vector3f self = wisp.getPosition();
        boolean changed = false;
        if (!victimChosen || players.playerPosition(victimId, self, scratch) == null) {
            changed = chooseVictim(self);
        }
        hasVictim = victimChosen && players.playerPosition(victimId, self, victimFeet) != null;
        inView = false;
        watched = false;
        hasLook = false;
        distance = Float.MAX_VALUE;
        if (!hasVictim) {
            return changed;
        }
        victimEye.set(victimFeet).add(0f, PlayerLocator.EYE_HEIGHT, 0f);
        victimInShade = shade.isShade(victimFeet);
        distance = self.distance(victimFeet);
        head.set(self).add(0f, HEAD_HEIGHT + wisp.renderLift(), 0f);
        chest.set(self).add(0f, CHEST_HEIGHT + wisp.renderLift(), 0f);
        hasLook = players.playerLook(victimId, self, victimLook) != null;
        if (hasLook && distance <= SIGHT_RANGE) {
            boolean headClear = players.canSeePlayer(victimId, self, head);
            boolean bodyClear = headClear || players.canSeePlayer(victimId, self, chest);
            boolean showing = !veiled || distance <= SHIMMER_RANGE;
            inView = showing && bodyClear && (PlayerGaze.inCone(victimEye, victimLook, head,
                    PlayerGaze.VIEW_HALF_ANGLE_DEG, SIGHT_RANGE)
                    || PlayerGaze.inCone(victimEye, victimLook, chest, PlayerGaze.VIEW_HALF_ANGLE_DEG, SIGHT_RANGE));
            // The eyes glow through the veil, so being stared at is judged on the head alone —
            // unless it has dimmed them, when only the shimmer up close gives it away.
            boolean eyesShowing = showing || !eyesDark;
            watched = eyesShowing && headClear && PlayerGaze.inCone(victimEye, victimLook, head,
                    PlayerGaze.FOCUS_HALF_ANGLE_DEG, SIGHT_RANGE);
        }
        return changed;
    }

    /** The nearest live player standing in the Shade, else the nearest at all. */
    private boolean chooseVictim(Vector3f self) {
        int[] best = {PlayerLocator.NEAREST, 0};
        float[] bestScore = {Float.MAX_VALUE};
        players.forEachPlayer(self, VICTIM_SEARCH_RADIUS, sensed -> {
            float score = sensed.position().distance(self) + (shade.isShade(sensed.position()) ? 0f : 1000f);
            if (score < bestScore[0]) {
                bestScore[0] = score;
                best[0] = sensed.id();
                best[1] = 1;
            }
        });
        if (best[1] == 0) {
            victimChosen = false;
            return false;
        }
        boolean changed = !victimChosen || best[0] != victimId;
        victimId = best[0];
        victimChosen = true;
        return changed;
    }

    int victimId() {
        return victimChosen ? victimId : PlayerLocator.NEAREST;
    }

    boolean hasVictim() {
        return hasVictim;
    }

    /** The victim's feet; meaningful only while {@link #hasVictim()}. Do not retain. */
    Vector3f victimFeet() {
        return victimFeet;
    }

    Vector3f victimEye() {
        return victimEye;
    }

    /** The victim's look direction, or null when it is not known. Do not retain. */
    Vector3f victimLook() {
        return hasLook ? victimLook : null;
    }

    boolean victimInShade() {
        return hasVictim && victimInShade;
    }

    float distance() {
        return distance;
    }

    /** Whether the victim could see the Wisp right now, were they paying attention. */
    boolean inView() {
        return inView;
    }

    /** Whether the victim is looking straight at the Wisp. */
    boolean watched() {
        return watched;
    }
}
