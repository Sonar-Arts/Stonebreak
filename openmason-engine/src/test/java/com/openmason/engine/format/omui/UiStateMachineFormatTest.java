package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostic.Severity;
import com.openmason.engine.format.omui.UiStateMachine.Driver;
import com.openmason.engine.format.omui.UiStateMachine.MachineState;
import com.openmason.engine.format.omui.UiStateMachine.MachineTransition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code animations/<id>.states.json} (feature {@code ui-states}) and clip value checks (#295). */
class UiStateMachineFormatTest {

    private static AnimTrack track(String target, String property, Object... timeValue) {
        List<AnimKey> keys = new ArrayList<>();
        for (int i = 0; i < timeValue.length; i += 2) {
            Object v = timeValue[i + 1];
            keys.add(new AnimKey(((Number) timeValue[i]).doubleValue(),
                v instanceof Number n ? UiValue.of(n.doubleValue()) : UiValue.of((String) v), UiEasing.EASE_OUT, Map.of()));
        }
        return new AnimTrack(target, property, keys, Map.of());
    }

    static OmuiArchive withMachine(UiStateMachine m, boolean declare) {
        UiManifest base = UiManifest.create("t:ui/btn", UiManifest.DocumentKind.SCREEN, "Button");
        UiManifest manifest = declare ? new UiManifest(base.schemaVersion(), base.documentId(), base.kind(),
            base.displayName(), base.uiApi(), base.layoutSemantics(), List.of(UiFeatures.STATES), base.hostApis(),
            base.providers(), base.unknown()) : base;
        return OmuiArchive.of(manifest, new UiDocument(UiNode.of("root", "Box", List.of(UiNode.of("frame", "Button",
                List.of()))), List.of(), null, null, Map.of()))
            .withAnimation(new UiAnimationClip("grow", 0.2, LoopMode.ONCE,
                List.of(track("frame", "style:scale", 0, 1, 0.2, 1.1)), List.of(), Map.of()))
            .withStateMachine(m);
    }

    static UiStateMachine look() {
        return new UiStateMachine("look", Driver.INTERACTION, "frame", "normal", List.of(
            new MachineState("hover", "grow", Map.of()), new MachineState("normal", null, Map.of())), List.of(
            new MachineTransition(UiStateMachine.ANY, "hover", "grow", 0.05, null, Map.of())), Map.of());
    }

    @Test
    void roundTripsUnderItsFeature() throws Exception {
        OmuiArchive doc = withMachine(look(), true);
        assertTrue(UiFeatures.used(doc).contains(UiFeatures.STATES));
        byte[] bytes = OmuiWriter.write(doc);
        OmuiReader.Result back = OmuiReader.read(bytes);
        assertEquals(doc, back.archive());
        assertTrue(OmuiWriter.entries(doc).containsKey("animations/look.states.json"));
        assertEquals("grow", back.archive().stateMachines().get("look").transition("normal", "hover").clip(),
            "* → to matches any source state");
    }

    @Test
    void theWriterRefusesAnUndeclaredFeature() {
        UiFormatException e = assertThrows(UiFormatException.class, () -> OmuiWriter.write(withMachine(look(), false)));
        assertTrue(e.diagnostics().stream().anyMatch(d -> d.code() == Code.UNDECLARED_FEATURE), e.getMessage());
    }

    @Test
    void brokenMachinesAreReportedWhereTheyAreWritten() {
        UiStateMachine broken = new UiStateMachine("bad", Driver.INTERACTION, "ghost", "idle", List.of(
            new MachineState("normal", "missing", Map.of()), new MachineState("wobble", null, Map.of())), List.of(
            new MachineTransition("normal", "gone", null, 0, "nope", Map.of()),
            new MachineTransition("normal", "gone", null, 0, null, Map.of())), Map.of());
        List<UiDiagnostic> d = OmuiValidator.validate(withMachine(broken, true));
        assertHas(d, x -> x.code() == Code.UNRESOLVED_REFERENCE && x.pointer().equals("/element"));
        assertHas(d, x -> x.code() == Code.UNRESOLVED_REFERENCE && x.pointer().equals("/initial"));
        assertHas(d, x -> x.code() == Code.UNRESOLVED_REFERENCE && x.pointer().equals("/states/0/clip"));
        assertHas(d, x -> x.code() == Code.INVALID_VALUE && x.pointer().equals("/states/1/name"));
        assertHas(d, x -> x.code() == Code.UNRESOLVED_REFERENCE && x.pointer().equals("/transitions/0/to"));
        assertHas(d, x -> x.code() == Code.UNRESOLVED_REFERENCE && x.pointer().equals("/transitions/0/reduced"));
        assertHas(d, x -> x.code() == Code.DUPLICATE_ID && x.pointer().equals("/transitions/1"));
    }

    @Test
    void clipKeysMustFitTheirStyleProperty() {
        OmuiArchive doc = withMachine(look(), true).withAnimation(new UiAnimationClip("bad", 1, LoopMode.ONCE, List.of(
            track("frame", "style:opacity", 0, 2),
            track("frame", "style:-sb-wobble", 0, 1),
            track("frame", "style:color", 0, "var(--accent)")), List.of(), Map.of()));
        List<UiDiagnostic> d = OmuiValidator.validate(doc);
        // Tracks are sorted by (target, property): -sb-wobble, color, opacity.
        assertHas(d, x -> x.severity() == Severity.ERROR && x.pointer().equals("/tracks/2/keys/0/value"));
        assertHas(d, x -> x.severity() == Severity.WARNING && x.code() == Code.UNKNOWN_FIELD_PRESERVED);
        assertTrue(d.stream().noneMatch(x -> x.pointer().startsWith("/tracks/1")), "var() is resolved at play time");
    }

    private static void assertHas(List<UiDiagnostic> d, Predicate<UiDiagnostic> p) {
        assertTrue(d.stream().anyMatch(p), d.toString());
    }
}
