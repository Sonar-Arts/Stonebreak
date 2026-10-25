package com.stonebreak.mobs.entities.ai.perception;

/**
 * What a mob's last perception tick worked out, for the F3 perception overlay (#321).
 *
 * @param meter     awareness meter after the tick
 * @param rate      meter change per second from the tick (detection × alertness, or −decay)
 * @param vision    sight contribution per second, before alertness
 * @param hearing   continuous-sound contribution per second, before alertness
 * @param light     light at the player, 0–1
 * @param effRange  vision range after darkness adjustment
 * @param memory    seconds a SEARCHING mob has left; 0 otherwise
 */
public record PerceptionSnapshot(float meter, float rate, float vision, float hearing, float light,
                                 float effRange, float memory) {
    public static final PerceptionSnapshot NONE = new PerceptionSnapshot(0f, 0f, 0f, 0f, 1f, 0f, 0f);
}
