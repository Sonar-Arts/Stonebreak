package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@code sprites} dependency ({@code <id>.sprites.json}, #294): named regions of one texture
 * plus their UI metadata. The sheet never holds pixels. A document binds it to its texture through
 * the sheet row's {@code requires} ({@code ui.assets.SpriteBinding}), so renames and import remaps
 * never edit sheet bytes; {@link #texture} records the id it was authored against. Elements reference
 * a region or skin as {@code <sheet id>#<name>} ({@link UiSpriteRef}), so a sheet can be
 * repacked or resized without touching the documents that use it.
 *
 * <p>Geometry is in source texture pixels; {@link #width}/{@link #height} record the texture
 * size the regions were authored against, so a resized texture is reported instead of sampled
 * out of bounds ({@link UiSpriteSheets#check}).
 *
 * @param texture dependency id of the texture the regions were authored against; editors add it as
 *                the sheet row's requirement
 * @param sprites regions, sorted by name
 * @param skins   per-state region sets, sorted by name; names are shared with sprites
 */
public record UiSpriteSheet(String texture, int width, int height, List<Sprite> sprites, List<Skin> skins,
                            Map<String, UiValue> unknown) {

    public UiSpriteSheet {
        Objects.requireNonNull(texture, "texture");
        sprites = Canon.sortedBy(sprites, Sprite::name);
        skins = Canon.sortedBy(skins, Skin::name);
        unknown = Canon.unknown(unknown);
    }

    public Optional<Sprite> sprite(String name) {
        return sprites.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    public Optional<Skin> skin(String name) {
        return skins.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    public UiSpriteSheet withSprites(List<Sprite> sprites) {
        return new UiSpriteSheet(texture, width, height, sprites, skins, unknown);
    }

    public UiSpriteSheet withSkins(List<Skin> skins) {
        return new UiSpriteSheet(texture, width, height, sprites, skins, unknown);
    }

    public UiSpriteSheet withTexture(String texture, int width, int height) {
        return new UiSpriteSheet(texture, width, height, sprites, skins, unknown);
    }

    /** How an image fills its element; the wire values of {@code -sb-image-scale}. */
    public enum ScaleMode implements WireEnum {
        STRETCH("stretch"), NINE_SLICE("nine-slice"), TILE("tile"), INTEGER("integer");

        private final String wire;

        ScaleMode(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }

        public static ScaleMode fromWire(String wire) {
            for (ScaleMode m : values()) {
                if (m.wire.equals(wire)) {
                    return m;
                }
            }
            return null;
        }
    }

    /** How a nine-slice edge or the centre fills the space between the corners. */
    public enum Fill implements WireEnum {
        STRETCH("stretch"), TILE("tile"),
        /** Centre only: a hollow frame. */
        HIDDEN("hidden");

        private final String wire;

        Fill(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /** Texture filtering; the wire values of {@code -sb-sampling}. */
    public enum Sampling implements WireEnum {
        NEAREST("nearest"), LINEAR("linear");

        private final String wire;

        Sampling(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /** Nine-slice insets in source pixels; corners keep their pixels at every size. */
    public record Slice(int left, int top, int right, int bottom) {
        public static final Slice NONE = new Slice(0, 0, 0, 0);

        public boolean isNone() {
            return left == 0 && top == 0 && right == 0 && bottom == 0;
        }
    }

    /** One animation frame: a region the size of its sprite at {@code (x, y)}, shown for {@code duration} seconds. */
    public record Frame(int x, int y, double duration, Map<String, UiValue> unknown) {
        public Frame {
            duration = Canon.num(duration);
            unknown = Canon.unknown(unknown);
        }

        public Frame(int x, int y, double duration) {
            this(x, y, duration, Map.of());
        }
    }

    /**
     * A named region.
     *
     * @param logicalWidth  intrinsic layout width in logical px; 0 = {@code w}
     * @param logicalHeight intrinsic layout height in logical px; 0 = {@code h}
     * @param pivotX        normalized anchor (0 = left, 1 = right) for natural-size placement and transforms
     * @param slice         nine-slice insets, {@link Slice#NONE} when the sprite is not sliced
     * @param edges         how sliced edges fill ({@link Fill#STRETCH} or {@link Fill#TILE})
     * @param center        how the sliced centre fills, {@link Fill#HIDDEN} for a frame
     * @param scale         default fill mode; null = nine-slice when sliced, else stretch.
     *                      An element's {@code -sb-image-scale} overrides it
     * @param sampling      null = the element's {@code -sb-sampling}
     * @param tint          {@code #RRGGBB[AA]} multiplied into the sprite, null = white
     * @param opacity       multiplied into the sprite's alpha
     * @param frames        animation frames in play order; empty = a still sprite
     * @param loop          what happens after the last frame
     */
    public record Sprite(String name, int x, int y, int w, int h, double logicalWidth, double logicalHeight,
                         double pivotX, double pivotY, Slice slice, Fill edges, Fill center, ScaleMode scale,
                         Sampling sampling, String tint, double opacity, List<Frame> frames, LoopMode loop,
                         Map<String, UiValue> unknown) {

        public Sprite {
            Objects.requireNonNull(name, "name");
            slice = slice == null ? Slice.NONE : slice;
            edges = edges == null ? Fill.STRETCH : edges;
            center = center == null ? Fill.STRETCH : center;
            loop = loop == null ? LoopMode.LOOP : loop;
            logicalWidth = Canon.num(logicalWidth);
            logicalHeight = Canon.num(logicalHeight);
            pivotX = Canon.num(pivotX);
            pivotY = Canon.num(pivotY);
            opacity = Canon.num(opacity);
            frames = Canon.list(frames);
            unknown = Canon.unknown(unknown);
        }

        /** A plain still region with every default. */
        public static Sprite of(String name, int x, int y, int w, int h) {
            return new Sprite(name, x, y, w, h, 0, 0, 0.5, 0.5, Slice.NONE, Fill.STRETCH, Fill.STRETCH, null, null,
                    null, 1, List.of(), LoopMode.LOOP, Map.of());
        }

        public double layoutWidth() {
            return logicalWidth > 0 ? logicalWidth : w;
        }

        public double layoutHeight() {
            return logicalHeight > 0 ? logicalHeight : h;
        }

        public ScaleMode effectiveScale() {
            return scale != null ? scale : slice.isNone() ? ScaleMode.STRETCH : ScaleMode.NINE_SLICE;
        }

        public boolean animated() {
            return frames.size() > 1;
        }

        public Sprite withName(String n) {
            return new Sprite(n, x, y, w, h, logicalWidth, logicalHeight, pivotX, pivotY, slice, edges, center, scale,
                    sampling, tint, opacity, frames, loop, unknown);
        }

        public Sprite withRect(int nx, int ny, int nw, int nh) {
            return new Sprite(name, nx, ny, nw, nh, logicalWidth, logicalHeight, pivotX, pivotY, slice, edges, center,
                    scale, sampling, tint, opacity, frames, loop, unknown);
        }

        public Sprite withLogicalSize(double lw, double lh) {
            return new Sprite(name, x, y, w, h, lw, lh, pivotX, pivotY, slice, edges, center, scale, sampling, tint,
                    opacity, frames, loop, unknown);
        }

        public Sprite withPivot(double px, double py) {
            return new Sprite(name, x, y, w, h, logicalWidth, logicalHeight, px, py, slice, edges, center, scale,
                    sampling, tint, opacity, frames, loop, unknown);
        }

        public Sprite withSlice(Slice s, Fill e, Fill c) {
            return new Sprite(name, x, y, w, h, logicalWidth, logicalHeight, pivotX, pivotY, s, e, c, scale, sampling,
                    tint, opacity, frames, loop, unknown);
        }

        public Sprite withLook(ScaleMode sc, Sampling sa, String t, double o) {
            return new Sprite(name, x, y, w, h, logicalWidth, logicalHeight, pivotX, pivotY, slice, edges, center, sc,
                    sa, t, o, frames, loop, unknown);
        }

        public Sprite withFrames(List<Frame> f, LoopMode l) {
            return new Sprite(name, x, y, w, h, logicalWidth, logicalHeight, pivotX, pivotY, slice, edges, center,
                    scale, sampling, tint, opacity, f, l, unknown);
        }
    }

    /**
     * Regions per interaction state, for buttons and other controls. A state without its own
     * region falls back to {@code normal}.
     */
    public record Skin(String name, String normal, String hover, String pressed, String disabled, String focused,
                       Map<String, UiValue> unknown) {

        public Skin {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(normal, "normal");
            unknown = Canon.unknown(unknown);
        }

        public static Skin of(String name, String normal) {
            return new Skin(name, normal, null, null, null, null, Map.of());
        }

        /** Every region the skin names, normal first. */
        public List<String> regions() {
            return java.util.stream.Stream.of(normal, hover, pressed, disabled, focused)
                    .filter(Objects::nonNull).toList();
        }

        public Skin withState(String state, String region) {
            return switch (state) {
                case "normal" -> new Skin(name, region, hover, pressed, disabled, focused, unknown);
                case "hover" -> new Skin(name, normal, region, pressed, disabled, focused, unknown);
                case "pressed" -> new Skin(name, normal, hover, region, disabled, focused, unknown);
                case "disabled" -> new Skin(name, normal, hover, pressed, region, focused, unknown);
                case "focused" -> new Skin(name, normal, hover, pressed, disabled, region, unknown);
                default -> throw new IllegalArgumentException("unknown skin state " + state);
            };
        }

        public Skin withName(String n) {
            return new Skin(n, normal, hover, pressed, disabled, focused, unknown);
        }

        /** The skin states, in the order an inspector lists them. */
        public static final List<String> STATES = List.of("normal", "hover", "pressed", "disabled", "focused");

        public String region(String state) {
            return switch (state) {
                case "normal" -> normal;
                case "hover" -> hover;
                case "pressed" -> pressed;
                case "disabled" -> disabled;
                case "focused" -> focused;
                default -> null;
            };
        }
    }
}
