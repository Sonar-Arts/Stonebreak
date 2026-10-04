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
                        k.optionalEnum("easing", UiEasing.class, UiEasing.LINEAR), k.unknown()));
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
