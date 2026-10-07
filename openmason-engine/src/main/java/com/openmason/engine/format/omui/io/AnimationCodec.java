package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimEvent;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.List;

/** {@code animations/<id>.anim.json} ↔ {@link UiAnimationClip}. */
public final class AnimationCodec {

    private AnimationCodec() {
    }

    public static UiAnimationClip read(String id, String entry, UiValue root, UiDiagnostics d) {
        ObjReader r = ObjReader.of(root, entry, "", d);
        String declared = r.requiredString("id");
        if (!declared.equals(id)) {
            r.error(Code.INCONSISTENT_MANIFEST, "id",
                    "Clip id '" + declared + "' does not match its entry name '" + id + "'");
        }
        double duration = r.requiredNumber("duration", 0, OmuiFormat.MAX_SECONDS);
        LoopMode loop = r.optionalEnum("loop", LoopMode.class, LoopMode.ONCE);
        List<AnimTrack> tracks = new ArrayList<>();
        for (ObjReader t : r.objects("tracks")) {
            List<AnimKey> keys = new ArrayList<>();
            for (ObjReader k : t.objects("keys")) {
                UiValue value = k.raw("value");
                if (value == null) {
                    k.error(Code.MISSING_FIELD, "value", "Required field 'value' is missing");
                    value = UiValue.NULL;
                }
                keys.add(new AnimKey(k.requiredNumber("time", 0, OmuiFormat.MAX_SECONDS), value,
                        k.optionalEnum("easing", UiEasing.class, UiEasing.LINEAR), bezier(k), k.unknown()));
            }
            tracks.add(new AnimTrack(t.requiredString("target"), t.requiredString("property"), keys, t.unknown()));
        }
        List<AnimEvent> events = new ArrayList<>();
        for (ObjReader e : r.objects("events")) {
            events.add(new AnimEvent(e.requiredNumber("time", 0, OmuiFormat.MAX_SECONDS), e.requiredString("name"),
                    e.unknown()));
        }
        return new UiAnimationClip(id, duration, loop, tracks, events, r.unknown());
    }

    /** Optional {@code bezier: [x1, y1, x2, y2]} (#295); a malformed one is an error. */
    static com.openmason.engine.format.omui.UiBezier bezier(ObjReader r) {
        UiValue raw = r.raw("bezier");
        if (raw == null) {
            return null;
        }
        com.openmason.engine.format.omui.UiBezier b = com.openmason.engine.format.omui.UiBezier.fromWire(raw);
        if (b == null) {
            r.error(Code.WRONG_TYPE, "bezier", "bezier must be four numbers [x1, y1, x2, y2]");
        }
        return b;
    }

    public static UiValue.Obj write(UiAnimationClip c) {
        return new ObjWriter()
                .put("id", c.id())
                .putNumber("duration", c.duration())
                .putEnumIfNot("loop", c.loop(), LoopMode.ONCE)
                .putList("tracks", c.tracks(), t -> new ObjWriter()
                        .put("target", t.target())
                        .put("property", t.property())
                        .putList("keys", t.keys(), k -> new ObjWriter()
                                .putNumber("time", k.time())
                                .put("value", k.value())
                                .putEnumIfNot("easing", k.easing(), UiEasing.LINEAR)
                                .putList("bezier", k.bezier() == null ? null : k.bezier().wire(), x -> x)
                                .putUnknown(k.unknown())
                                .build())
                        .putUnknown(t.unknown())
                        .build())
                .putList("events", c.events(), e -> new ObjWriter()
                        .putNumber("time", e.time())
                        .put("name", e.name())
                        .putUnknown(e.unknown())
                        .build())
                .putUnknown(c.unknown())
                .build();
    }
}
