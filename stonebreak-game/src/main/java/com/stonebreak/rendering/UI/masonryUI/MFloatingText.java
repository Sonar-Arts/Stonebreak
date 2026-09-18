package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Floating numbers and words any screen can raise: damage, a pickup ("+3 Wood"), XP, a warning, a
 * rejected action. A manager rather than a widget: it has no bounds, only the texts in flight.
 *
 * <p>A floater takes its <b>screen</b> position once, at {@link #spawn}, and from then on moves
 * purely in screen space: a ballistic arc, then a fade. Whatever it was born from (a body in the
 * world, a HUD row) may move or a camera may cut; the number never teleports after it. Project a
 * world point with {@link MWorldMarker} first and hand the pixels in.
 *
 * <p>Several texts can be born on one spot in the same frame (a number, a grade word and a status
 * name from one blow). {@link Lane}s keep the three kinds apart vertically, and a run of spawns in
 * one lane at one anchor is staggered in time and fanned out in space.
 *
 * <p>Deterministic: a floater's scatter is a function of how many were spawned before it since
 * {@link #clear}, never of a clock or a random. State advances only in {@link #update}; painting
 * and every position query are pure. Positions are computed for a {@code scale} supplied at paint
 * time, so the manager stays free of any settings singleton.
 */
public final class MFloatingText {

    public static final int DEFAULT_CAP = 24;

    /** Simultaneous floaters in one lane on one anchor leave this far apart in time… */
    static final float STAGGER_SECONDS = 0.07f;
    /** …and a run of spawns counts as "simultaneous" until the anchor has been quiet this long. */
    static final float STAGGER_MEMORY_SECONDS = 0.3f;
    /** Spawns within this many pixels of a burst's first spawn share its anchor. */
    static final float SAME_ANCHOR_PX = 32f;
    private static final float FADE_FROM = 0.55f;
    private static final float POP_SECONDS = 0.12f;
    private static final float POP_EXTRA = 0.45f;
    private static final int MAX_BURSTS = 32;
    /** Gravity relative to the launch speed: fixes the arc's shape whatever the style's energy. */
    private static final float GRAVITY_PER_RISE = 520f / 175f;

    /**
     * Vertical lane a floater starts in, relative to its anchor (design pixels, scaled at paint time).
     */
    public enum Lane {
        NUMBER(0f), WORD(-58f), STATUS(62f);

        private final float offset;

        Lane(float offset) { this.offset = offset; }

        public float offset() { return offset; }
    }

    /**
     * Look and throw of a floater.
     *
     * @param color     ARGB text colour (the outline is the shared {@link MStyle#OUTLINE_DARK})
     * @param fontSize  design size, scaled at paint time
     * @param lane      vertical lane it starts in
     * @param riseSpeed launch speed upward, design px/s (gravity follows from it)
     * @param spread    greatest sideways speed, design px/s; 0 = straight up
     * @param lifetime  seconds from its start to gone; the last 45% fades
     * @param pop       lands big and settles over the first 0.12 s
     */
    public record Style(int color, float fontSize, Lane lane, float riseSpeed, float spread, float lifetime,
                        boolean pop) {

        public Style {
            fontSize = fontSize > 0f && fontSize < 1000f ? fontSize : MStyle.FONT_BUTTON;
            lane = lane == null ? Lane.NUMBER : lane;
            riseSpeed = riseSpeed >= 0f && riseSpeed < 1.0e5f ? riseSpeed : 0f;
            spread = spread >= 0f && spread < 1.0e5f ? spread : 0f;
            lifetime = lifetime > 0f && lifetime < 1.0e4f ? lifetime : 0.9f;
        }

        /** The plain number: a hit, a pickup count. */
        public static Style number() {
            return new Style(MStyle.TEXT_PRIMARY, 26f, Lane.NUMBER, 175f, 62f, 0.9f, true);
        }

        /** The number that matters: larger, gold, thrown harder. */
        public static Style emphasis() {
            return new Style(MStyle.TEXT_ACCENT, 36f, Lane.NUMBER, 200f, 71f, 0.9f, true);
        }

        /** A word beside the number ("PERFECT", "LEVEL UP"): its own lane above. */
        public static Style word() {
            return new Style(MStyle.TEXT_PRIMARY, 23f, Lane.WORD, 140f, 50f, 0.9f, true);
        }

        /** A quiet aside (a status name, a small gain, a refusal): its own lane below, thrown softly. */
        public static Style minor() {
            return new Style(MStyle.TEXT_SECONDARY, 18f, Lane.STATUS, 120f, 43f, 0.9f, false);
        }

        public Style withColor(int value) { return new Style(value, fontSize, lane, riseSpeed, spread, lifetime, pop); }
        public Style withFontSize(float value) { return new Style(color, value, lane, riseSpeed, spread, lifetime, pop); }
        public Style withLane(Lane value) { return new Style(color, fontSize, value, riseSpeed, spread, lifetime, pop); }
        public Style withLifetime(float value) { return new Style(color, fontSize, lane, riseSpeed, spread, value, pop); }
    }

    /** One text in flight. Everything about its path is fixed at spawn; only {@code delay}/{@code age} move. */
    public static final class Floater {
        private final String text;
        private final Style style;
        private final float anchorX, anchorY;
        private final float bandMinY, bandMaxY, sideMinX, sideMaxX;
        private final int serial;
        private final int slot;
        private float delay;
        private float age;

        private Floater(String text, Style style, float anchorX, float anchorY, float bandMinY, float bandMaxY,
                        float sideMinX, float sideMaxX, int serial, int slot) {
            this.text = text;
            this.style = style;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.bandMinY = bandMinY;
            this.bandMaxY = bandMaxY;
            this.sideMinX = sideMinX;
            this.sideMaxX = sideMaxX;
            this.serial = serial;
            this.slot = slot;
            this.delay = slot * STAGGER_SECONDS;
        }

        public String text() { return text; }
        public Style style() { return style; }
        /** Position in its burst: 0 for the first spawn on a quiet anchor. */
        public int slot() { return slot; }
        /** Seconds since it started moving (0 while its stagger delay runs). */
        public float age() { return age; }
        /** True once its stagger delay has run out; only started floaters are drawn. */
        public boolean started() { return delay <= 0f; }

        public float alpha() {
            float t = age / style.lifetime();
            return t <= FADE_FROM ? 1f : Math.max(0f, 1f - (t - FADE_FROM) / (1f - FADE_FROM));
        }

        /** Size multiplier of the landing pop: 1.45 at birth, 1 from 0.12 s on. */
        public float popScale() {
            return style.pop() ? 1f + POP_EXTRA * (1f - Math.min(1f, age / POP_SECONDS)) : 1f;
        }

        /** Where it starts {@code {x, y}}: anchor + burst fan + lane, kept where the whole arc fits the band. */
        public float[] origin(float scale) {
            float s = usable(scale) ? scale : 1f;
            // A fan of slots so simultaneous spawns never stack on one spot, in the style's own lane.
            float dx = (Math.floorMod(slot + 1, 3) - 1) * 64f * s;
            float dy = (style.lane().offset() - Math.floorMod(slot, 4) * 30f) * s;
            float vy = launchSpeed() * s, gravity = gravity() * s;
            float apex = gravity > 0f ? vy * vy / (2f * gravity) : vy * style.lifetime();
            // Past its apex the arc only falls, so its lowest point is where it is at the end of its life.
            float life = style.lifetime();
            float descent = Math.max(0f, -vy * life + 0.5f * gravity * life * life);
            float x = clamp(anchorX + dx, sideMinX, sideMaxX);
            // Start where the whole arc fits: apex under the ceiling, final fall above the floor. A band
            // too short for the arc keeps the floor (the text may then rise out of the top).
            float floor = Math.max(bandMinY, bandMaxY - descent);
            float ceiling = Math.min(bandMinY + apex, floor);
            return new float[]{x, clamp(anchorY + dy, ceiling, floor)};
        }

        /** Current centre {@code {x, y}} at {@code scale}: the origin plus the ballistic arc so far. */
        public float[] position(float scale) {
            float s = usable(scale) ? scale : 1f;
            float[] at = origin(s);
            // Alternate sides; the spread comes from the spawn serial, not from a random.
            float side = (serial & 1) == 0 ? 1f : -1f;
            float share = 0.45f + 0.55f * (Math.floorMod(serial * 7, 5) / 4f);
            float vx = side * share * style.spread() * s;
            float vy = -launchSpeed() * s;
            at[0] += vx * age;
            at[1] += vy * age + 0.5f * gravity() * s * age * age;
            return at;
        }

        private float launchSpeed() {
            return style.riseSpeed() * (1f + 0.07f * Math.floorMod(serial * 3, 4));
        }

        private float gravity() {
            return style.riseSpeed() * GRAVITY_PER_RISE;
        }
    }

    /** A run of spawns in one lane around one spot. */
    private static final class Burst {
        final float x, y;
        final Lane lane;
        int count;
        float quiet;

        Burst(float x, float y, Lane lane) {
            this.x = x;
            this.y = y;
            this.lane = lane;
        }
    }

    private final List<Floater> live = new ArrayList<>();
    private final List<Burst> bursts = new ArrayList<>();
    private int cap = DEFAULT_CAP;
    private int spawned;
    private float bandMinY = -Float.MAX_VALUE, bandMaxY = Float.MAX_VALUE;
    private float sideMinX = -Float.MAX_VALUE, sideMaxX = Float.MAX_VALUE;

    // ─────────────────────────────────────────────── Config

    /** Most floaters alive at once (default {@value #DEFAULT_CAP}); the oldest, most faded, goes first. */
    public MFloatingText cap(int max) {
        this.cap = Math.max(1, max);
        trim();
        return this;
    }

    /**
     * Vertical band, in window pixels, that floaters spawned from now on stay inside for their whole
     * arc (so a number born near the top never rises into a banner). NaN or an inverted range clears it.
     */
    public MFloatingText band(float minY, float maxY) {
        boolean ok = !Float.isNaN(minY) && !Float.isNaN(maxY) && minY <= maxY;
        this.bandMinY = ok ? minY : -Float.MAX_VALUE;
        this.bandMaxY = ok ? maxY : Float.MAX_VALUE;
        return this;
    }

    /**
     * Horizontal limits for floaters spawned from now on: they start inside, and a long word near an
     * edge is nudged so it is drawn fully inside. NaN or an inverted range clears it.
     */
    public MFloatingText sides(float minX, float maxX) {
        boolean ok = !Float.isNaN(minX) && !Float.isNaN(maxX) && minX <= maxX;
        this.sideMinX = ok ? minX : -Float.MAX_VALUE;
        this.sideMaxX = ok ? maxX : Float.MAX_VALUE;
        return this;
    }

    // ─────────────────────────────────────────────── Spawn / update

    /**
     * Raises {@code text} from the screen point {@code (screenX, screenY)}.
     *
     * @return the floater, or null when there is nothing to show (empty text, no style, NaN position)
     */
    public Floater spawn(String text, Style style, float screenX, float screenY) {
        if (text == null || text.isEmpty() || style == null || !finite(screenX) || !finite(screenY)) return null;
        Burst burst = burstFor(screenX, screenY, style.lane());
        int slot = burst.count++;
        burst.quiet = 0f;
        Floater f = new Floater(text, style, screenX, screenY, bandMinY, bandMaxY, sideMinX, sideMaxX,
                spawned++, slot);
        live.add(f);
        trim();
        return f;
    }

    private Burst burstFor(float x, float y, Lane lane) {
        for (Burst b : bursts) {
            if (b.lane == lane && Math.abs(b.x - x) <= SAME_ANCHOR_PX && Math.abs(b.y - y) <= SAME_ANCHOR_PX) return b;
        }
        Burst fresh = new Burst(x, y, lane);
        bursts.add(fresh);
        while (bursts.size() > MAX_BURSTS) bursts.remove(0);
        return fresh;
    }

    private void trim() {
        while (live.size() > cap) live.remove(0);
    }

    /** Ages the floaters and forgets quiet anchors. Negative or NaN {@code dt} is no time at all. */
    public void update(float dt) {
        float step = dt > 0f && finite(dt) ? dt : 0f;
        if (step <= 0f) return;
        for (Floater f : live) {
            if (f.delay > 0f) {
                f.delay -= step;
                if (f.delay < 0f) {           // what is left of the step after the delay is flight time
                    f.age -= f.delay;
                    f.delay = 0f;
                }
            } else {
                f.age += step;
            }
        }
        live.removeIf(f -> f.age >= f.style.lifetime());
        for (Burst b : bursts) b.quiet += step;
        bursts.removeIf(b -> b.quiet >= STAGGER_MEMORY_SECONDS);
    }

    public void clear() {
        live.clear();
        bursts.clear();
        spawned = 0;
    }

    public int liveCount() { return live.size(); }

    /** The live floaters, oldest first (read-only). */
    public List<Floater> live() { return Collections.unmodifiableList(live); }

    // ─────────────────────────────────────────────── Paint

    /** Draws every started floater at {@code scale} (fonts, lane offsets and speeds all follow it). */
    public void render(MasonryUI ui, float scale) {
        if (ui == null || live.isEmpty() || !usable(scale)) return;
        Canvas canvas = ui.canvas();
        if (canvas == null) return;
        for (Floater f : live) {
            if (!f.started()) continue;
            float alpha = f.alpha();
            if (alpha <= 0f) continue;
            Font font = ui.fonts().get(f.style.fontSize(), scale);
            if (font == null) continue;
            float[] p = f.position(scale);
            // A long word near an edge stays fully inside the limits (when it can fit at all).
            float half = MPainter.measureWidth(font, f.text) / 2f + 6f * scale;
            if (f.sideMaxX - f.sideMinX > 2f * half) p[0] = clamp(p[0], f.sideMinX + half, f.sideMaxX - half);
            // Lands big and settles: the pop sells the moment without moving the text's centre.
            float pop = f.popScale();
            canvas.save();
            try {
                canvas.translate(p[0], p[1]);
                canvas.scale(pop, pop);
                MPainter.drawTextOutlined(canvas, f.text, 0f, MPainter.baselineFor(0f, font.getSize()), font,
                        MColor.fade(f.style.color(), alpha), MPainter.Align.CENTER,
                        Math.max(2f, font.getSize() * 0.16f));
            } finally {
                canvas.restore();
            }
        }
    }

    // ─────────────────────────────────────────────── Helpers

    private static boolean usable(float scale) {
        return scale > 0f && finite(scale);
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
