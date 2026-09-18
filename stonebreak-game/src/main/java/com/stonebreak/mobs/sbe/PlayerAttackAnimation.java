package com.stonebreak.mobs.sbe;

/**
 * Plays a complete authored body attack independently of mining/combat pulse timing.
 * Pulses during a punch do not restart it; sustained attacking starts another punch
 * after recovery. Shared by local and replicated player figures.
 */
public final class PlayerAttackAnimation {
    private final OverlayAnimState overlay = new OverlayAnimState();
    private boolean playing;
    private float duration;

    public void update(float dt, boolean attacking, SbeEntityAsset asset) {
        if (!Float.isFinite(dt) || dt < 0f) return;
        if (playing) {
            overlay.update(dt, true);
            if (overlay.time() >= duration) {
                playing = false;
                overlay.reset();
            }
        } else if (attacking) {
            var clip = asset == null ? null : asset.clipFor("attacking");
            duration = clip != null && Float.isFinite(clip.duration()) && clip.duration() > 0f
                    ? clip.duration() : 0.78f;
            playing = true;
            overlay.update(0f, true);
        }
    }

    public OverlayAnimState overlay() { return overlay; }
}
