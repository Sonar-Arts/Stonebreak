package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiStyleSheet.StyleRule;
import com.openmason.engine.format.omui.UiStyleSheet.StyleTransition;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.List;

/** {@code styles/<id>.uss.json} ↔ {@link UiStyleSheet}. */
public final class StyleCodec {

    private StyleCodec() {
    }

    public static UiStyleSheet read(String id, String entry, UiValue root, UiDiagnostics d) {
        ObjReader r = ObjReader.of(root, entry, "", d);
        String declared = r.requiredString("id");
        if (!declared.equals(id)) {
            r.error(Code.INCONSISTENT_MANIFEST, "id",
                    "Sheet id '" + declared + "' does not match its entry name '" + id + "'");
        }
        List<StyleRule> rules = new ArrayList<>();
        for (ObjReader rule : r.objects("rules")) {
            List<StyleTransition> transitions = new ArrayList<>();
            for (ObjReader t : rule.objects("transitions")) {
                transitions.add(new StyleTransition(t.requiredString("property"),
                        t.requiredNumber("duration", 0, OmuiFormat.MAX_SECONDS),
                        t.optionalEnum("easing", UiEasing.class, UiEasing.LINEAR),
                        t.optionalNumber("delay", 0, 0, OmuiFormat.MAX_SECONDS), AnimationCodec.bezier(t), t.unknown()));
            }
            rules.add(new StyleRule(rule.requiredString("selector"), rule.freeMap("style"), transitions,
                    rule.unknown()));
        }
        return new UiStyleSheet(id, r.freeMap("variables"), r.stringList("customStates"), rules, r.unknown());
    }

    public static UiValue.Obj write(UiStyleSheet s) {
        return new ObjWriter()
                .put("id", s.id())
                .putMap("variables", s.variables())
                .putStrings("customStates", s.customStates())
                .putList("rules", s.rules(), rule -> new ObjWriter()
                        .put("selector", rule.selector())
                        .putMap("style", rule.style())
                        .putList("transitions", rule.transitions(), t -> new ObjWriter()
                                .put("property", t.property())
                                .putNumber("duration", t.duration())
                                .putEnumIfNot("easing", t.easing(), UiEasing.LINEAR)
                                .putNumberIfNot("delay", t.delay(), 0)
                                .putList("bezier", t.bezier() == null ? null : t.bezier().wire(), x -> x)
                                .putUnknown(t.unknown())
                                .build())
                        .putUnknown(rule.unknown())
                        .build())
                .putUnknown(s.unknown())
                .build();
    }
}
