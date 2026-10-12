package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiStateMachine;
import com.openmason.engine.format.omui.UiStateMachine.Driver;
import com.openmason.engine.format.omui.UiStateMachine.MachineState;
import com.openmason.engine.format.omui.UiStateMachine.MachineTransition;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.List;

/** {@code animations/<id>.states.json} ↔ {@link UiStateMachine} (#295). */
public final class StateMachineCodec {

    private StateMachineCodec() {
    }

    public static UiStateMachine read(String id, String entry, UiValue root, UiDiagnostics d) {
        ObjReader r = ObjReader.of(root, entry, "", d);
        String declared = r.requiredString("id");
        if (!declared.equals(id)) {
            r.error(Code.INCONSISTENT_MANIFEST, "id",
                    "State machine id '" + declared + "' does not match its entry name '" + id + "'");
        }
        Driver driver = r.optionalEnum("driver", Driver.class, Driver.MANUAL);
        String element = r.optionalString("element", null);
        String initial = r.requiredString("initial");
        List<MachineState> states = new ArrayList<>();
        for (ObjReader s : r.objects("states")) {
            states.add(new MachineState(s.requiredString("name"), s.optionalString("clip", null), s.unknown()));
        }
        List<MachineTransition> transitions = new ArrayList<>();
        for (ObjReader t : r.objects("transitions")) {
            transitions.add(new MachineTransition(t.optionalString("from", UiStateMachine.ANY), t.requiredString("to"),
                    t.optionalString("clip", null), t.optionalNumber("blend", 0, 0, OmuiFormat.MAX_SECONDS),
                    t.optionalString("reduced", null), t.unknown()));
        }
        return new UiStateMachine(id, driver, element, initial, states, transitions, r.unknown());
    }

    public static UiValue.Obj write(UiStateMachine m) {
        ObjWriter w = new ObjWriter()
                .put("id", m.id())
                .putEnumIfNot("driver", m.driver(), Driver.MANUAL);
        if (m.element() != null) {
            w.put("element", m.element());
        }
        return w.put("initial", m.initial())
                .putList("states", m.states(), s -> {
                    ObjWriter sw = new ObjWriter().put("name", s.name());
                    if (s.clip() != null) {
                        sw.put("clip", s.clip());
                    }
                    return sw.putUnknown(s.unknown()).build();
                })
                .putList("transitions", m.transitions(), t -> {
                    ObjWriter tw = new ObjWriter().putIfNot("from", t.from(), UiStateMachine.ANY).put("to", t.to());
                    if (t.clip() != null) {
                        tw.put("clip", t.clip());
                    }
                    tw.putNumberIfNot("blend", t.blend(), 0);
                    if (t.reduced() != null) {
                        tw.put("reduced", t.reduced());
                    }
                    return tw.putUnknown(t.unknown()).build();
                })
                .putUnknown(m.unknown())
                .build();
    }
}
