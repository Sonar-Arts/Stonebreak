package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiSpriteSheet;
import com.openmason.engine.format.omui.UiSpriteSheet.Fill;
import com.openmason.engine.format.omui.UiSpriteSheet.Frame;
import com.openmason.engine.format.omui.UiSpriteSheet.Sampling;
import com.openmason.engine.format.omui.UiSpriteSheet.ScaleMode;
import com.openmason.engine.format.omui.UiSpriteSheet.Skin;
import com.openmason.engine.format.omui.UiSpriteSheet.Slice;
import com.openmason.engine.format.omui.UiSpriteSheet.Sprite;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code <id>.sprites.json} ↔ {@link UiSpriteSheet} (#294). Canonical JSON like every other UI
 * entry: schema field order, defaults omitted, unknown fields preserved. Structural rules are
 * checked here (names, ranges, skin references); geometry against the actual texture is
 * {@link com.openmason.engine.format.omui.UiSpriteSheets#check}.
 */
public final class SpriteSheetCodec {

    public static final String FORMAT = "omui-sprites";
    public static final int VERSION = 1;
    /** Largest texture side a sheet may describe. */
    public static final int MAX_SIZE = 16384;

    private static final Pattern COLOR = Pattern.compile("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})");

    private SpriteSheetCodec() {
    }

    /** Decodes and checks a sheet; throws with every finding when it has errors. */
    public static UiSpriteSheet read(byte[] bytes, String entry) throws UiFormatException {
        UiDiagnostics d = new UiDiagnostics();
        UiSpriteSheet sheet = read(bytes, entry, d);
        if (sheet == null) {
            d.error(Code.MALFORMED_JSON, entry, "", "Not a sprite sheet");
        }
        d.throwIfErrors("Sprite sheet " + entry);
        return sheet;
    }

    /** @return the sheet (possibly partial, check {@code d}) or null when the bytes are not a JSON object */
    public static UiSpriteSheet read(byte[] bytes, String entry, UiDiagnostics d) {
        UiValue root = CanonicalJson.parse(bytes, entry, d);
        if (root == null) {
            return null;
        }
        return read(root, entry, d);
    }

    public static UiSpriteSheet read(UiValue root, String entry, UiDiagnostics d) {
        ObjReader r = ObjReader.of(root, entry, "", d);
        String format = r.requiredString("format");
        if (!format.isEmpty() && !FORMAT.equals(format)) {
            r.error(Code.INVALID_VALUE, "format", "Expected \"" + FORMAT + "\", found \"" + format + "\"");
        }
        int version = r.requiredInt("version", 1, 1_000_000);
        if (version > VERSION) {
            r.error(Code.UNSUPPORTED_SCHEMA_VERSION, "version",
                    "Sprite sheet version " + version + " is newer than this reader (" + VERSION + ")");
        }
        String texture = r.requiredString("texture");
        if (!texture.isEmpty() && !OmuiFormat.LOGICAL_ID.matcher(texture).matches()) {
            r.error(Code.INVALID_ID, "texture", "'" + texture + "' is not a dependency id");
        }
        int width = r.requiredInt("width", 1, MAX_SIZE);
        int height = r.requiredInt("height", 1, MAX_SIZE);
        Set<String> names = new HashSet<>();
        List<Sprite> sprites = new ArrayList<>();
        for (ObjReader s : r.objects("sprites")) {
            Sprite sprite = sprite(s);
            name(s, sprite.name(), names);
            sprites.add(sprite);
        }
        List<Skin> skins = new ArrayList<>();
        for (ObjReader s : r.objects("skins")) {
            Skin skin = new Skin(s.requiredString("name"), s.requiredString("normal"),
                    s.optionalString("hover", null), s.optionalString("pressed", null),
                    s.optionalString("disabled", null), s.optionalString("focused", null), s.unknown());
            name(s, skin.name(), names);
            for (String state : Skin.STATES) {
                String region = skin.region(state);
                if (region != null && sprites.stream().noneMatch(sp -> sp.name().equals(region))) {
                    s.error(Code.UNRESOLVED_REFERENCE, state, "Skin '" + skin.name() + "' names no sprite '"
                            + region + "'");
                }
            }
            skins.add(skin);
        }
        return new UiSpriteSheet(texture, width, height, sprites, skins, r.unknown());
    }

    private static void name(ObjReader r, String name, Set<String> names) {
        if (!OmuiFormat.LOCAL_ID.matcher(name).matches()) {
            r.error(Code.INVALID_ID, "name", "Invalid sprite name '" + name + "'");
        } else if (!names.add(name)) {
            r.error(Code.DUPLICATE_ID, "name", "Two sprites or skins are named '" + name + "'");
        }
    }

    private static Sprite sprite(ObjReader s) {
        String name = s.requiredString("name");
        int x = s.requiredInt("x", 0, MAX_SIZE);
        int y = s.requiredInt("y", 0, MAX_SIZE);
        int w = s.requiredInt("w", 1, MAX_SIZE);
        int h = s.requiredInt("h", 1, MAX_SIZE);
        double lw = s.optionalNumber("logicalWidth", 0, 0, MAX_SIZE);
        double lh = s.optionalNumber("logicalHeight", 0, 0, MAX_SIZE);
        double px = s.optionalNumber("pivotX", 0.5, 0, 1);
        double py = s.optionalNumber("pivotY", 0.5, 0, 1);
        Slice slice = Slice.NONE;
        ObjReader sl = s.optionalObject("slice");
        if (sl != null) {
            slice = new Slice(sl.optionalInt("left", 0, 0, MAX_SIZE), sl.optionalInt("top", 0, 0, MAX_SIZE),
                    sl.optionalInt("right", 0, 0, MAX_SIZE), sl.optionalInt("bottom", 0, 0, MAX_SIZE));
            sl.unknown();
        }
        Fill edges = s.optionalEnum("edges", Fill.class, Fill.STRETCH);
        if (edges == Fill.HIDDEN) {
            s.error(Code.INVALID_VALUE, "edges", "Edges stretch or tile; only the centre can be hidden");
            edges = Fill.STRETCH;
        }
        Fill center = s.optionalEnum("center", Fill.class, Fill.STRETCH);
        ScaleMode scale = s.optionalEnum("scale", ScaleMode.class, null);
        Sampling sampling = s.optionalEnum("sampling", Sampling.class, null);
        String tint = s.optionalString("tint", null);
        if (tint != null && !COLOR.matcher(tint).matches()) {
            s.error(Code.INVALID_VALUE, "tint", "Expected \"#RRGGBB\" or \"#RRGGBBAA\"");
            tint = null;
        }
        double opacity = s.optionalNumber("opacity", 1, 0, 1);
        List<Frame> frames = new ArrayList<>();
        List<ObjReader> fr = s.objects("frames");
        for (ObjReader f : fr) {
            double duration = f.requiredNumber("duration", 0, OmuiFormat.MAX_SECONDS);
            if (duration <= 0 && f.has("duration")) {
                f.error(Code.INVALID_VALUE, "duration", "A frame must last longer than 0 s");
            }
            frames.add(new Frame(f.requiredInt("x", 0, MAX_SIZE), f.requiredInt("y", 0, MAX_SIZE), duration,
                    f.unknown()));
        }
        LoopMode loop = s.optionalEnum("loop", LoopMode.class, LoopMode.LOOP);
        return new Sprite(name, x, y, w, h, lw, lh, px, py, slice, edges, center, scale, sampling, tint, opacity,
                frames, loop, s.unknown());
    }

    public static byte[] write(UiSpriteSheet sheet) {
        return CanonicalJson.write(encode(sheet));
    }

    public static UiValue.Obj encode(UiSpriteSheet sheet) {
        return new ObjWriter()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("texture", sheet.texture())
                .put("width", sheet.width())
                .put("height", sheet.height())
                .putList("sprites", sheet.sprites(), SpriteSheetCodec::sprite)
                .putList("skins", sheet.skins(), k -> new ObjWriter()
                        .put("name", k.name())
                        .put("normal", k.normal())
                        .putIfNot("hover", k.hover(), null)
                        .putIfNot("pressed", k.pressed(), null)
                        .putIfNot("disabled", k.disabled(), null)
                        .putIfNot("focused", k.focused(), null)
                        .putUnknown(k.unknown())
                        .build())
                .putUnknown(sheet.unknown())
                .build();
    }

    private static UiValue sprite(Sprite s) {
        ObjWriter w = new ObjWriter()
                .put("name", s.name())
                .put("x", s.x())
                .put("y", s.y())
                .put("w", s.w())
                .put("h", s.h())
                .putNumberIfNot("logicalWidth", s.logicalWidth(), 0)
                .putNumberIfNot("logicalHeight", s.logicalHeight(), 0)
                .putNumberIfNot("pivotX", s.pivotX(), 0.5)
                .putNumberIfNot("pivotY", s.pivotY(), 0.5);
        if (!s.slice().isNone()) {
            w.put("slice", new ObjWriter()
                    .putIfNot("left", s.slice().left(), 0)
                    .putIfNot("top", s.slice().top(), 0)
                    .putIfNot("right", s.slice().right(), 0)
                    .putIfNot("bottom", s.slice().bottom(), 0)
                    .build());
        }
        return w.putEnumIfNot("edges", s.edges(), Fill.STRETCH)
                .putEnumIfNot("center", s.center(), Fill.STRETCH)
                .putEnum("scale", s.scale())
                .putEnum("sampling", s.sampling())
                .putIfNot("tint", s.tint(), null)
                .putNumberIfNot("opacity", s.opacity(), 1)
                .putList("frames", s.frames(), f -> new ObjWriter()
                        .put("x", f.x())
                        .put("y", f.y())
                        .putNumber("duration", f.duration())
                        .putUnknown(f.unknown())
                        .build())
                .putEnumIfNot("loop", s.loop(), LoopMode.LOOP)
                .putUnknown(s.unknown())
                .build();
    }
}
