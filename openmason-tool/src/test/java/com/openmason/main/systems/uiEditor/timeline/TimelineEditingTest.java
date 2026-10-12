package com.openmason.main.systems.uiEditor.timeline;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiStateMachine;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.ui.assets.export.ExportMode;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.main.systems.uiEditor.command.AnimationCommands;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiDocumentService;
import com.openmason.main.systems.uiEditor.service.UiDocumentTemplates;
import com.openmason.main.systems.uiEditor.service.UiProjectContext;
import com.openmason.main.systems.uiEditor.service.UiRecoveryService;
import com.openmason.main.systems.uiEditor.timeline.ClipEdits.KeyId;
import com.openmason.main.systems.uiEditor.timeline.ClipEdits.TrackId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Timeline authoring (#295): clip edits as undoable commands, scrubbing that never edits, save/export round trips. */
class TimelineEditingTest {

    static final Path PAUSE = Path.of("../openmason-engine/src/test/resources/ui/omui/pause_menu.omui");
    static final TrackId OPACITY = new TrackId("panel", "style:opacity");
    static final TrackId SLIDE = new TrackId("panel", "style:translate-y");

    @TempDir
    Path project;
    @TempDir
    Path recoveryDir;

    private UiEditorDocument doc;

    @BeforeEach
    void open() throws Exception {
        doc = new UiEditorDocument(OmuiReader.read(PAUSE).archive(), PAUSE, UiEditorDocument.Origin.FILE, null);
    }

    private UiAnimationClip clip(String id) {
        return doc.archive().animations().get(id);
    }

    /** An "intro" clip: opacity 0 → 1 (ease-out) and translate-y 30 → 0 over 0.5 s. */
    private void authorIntro() {
        assertTrue(doc.execute(AnimationCommands.addClip("intro", 0.5)), doc.lastMessage());
        assertTrue(doc.execute(AnimationCommands.editClip("Keys", "intro", null, c -> {
            c = ClipEdits.setKey(c, OPACITY, 0, UiValue.of(0), UiEasing.EASE_OUT);
            c = ClipEdits.setKey(c, OPACITY, 0.5, UiValue.of(1), null);
            c = ClipEdits.setKey(c, SLIDE, 0, UiValue.of(30), null);
            return ClipEdits.setKey(c, SLIDE, 0.5, UiValue.of(0), null);
        })), doc.lastMessage());
        doc.endInteraction();
    }

    @Test
    void keyEditsAreValidatedAndOneUndoStepEach() {
        authorIntro();
        UiAnimationClip c = clip("intro");
        assertEquals(2, c.tracks().size());
        assertEquals(UiEasing.EASE_OUT, ClipEdits.track(c, OPACITY).keys().getFirst().easing());

        assertThrows(IllegalArgumentException.class, () -> ClipEdits.setDuration(c, 0.25), "would cut keys off");
        assertThrows(IllegalArgumentException.class, () -> ClipEdits.moveKeys(c,
            List.of(new KeyId(OPACITY, 0)), 0.5), "onto the other key");
        assertFalse(doc.execute(AnimationCommands.editClip("Move", "intro", null,
            x -> ClipEdits.moveKeys(x, List.of(new KeyId(OPACITY, 0)), 0.5).clip())));
        assertEquals(c, clip("intro"), "a refused edit leaves the clip");

        // A drag is many frames, one undo step.
        long undoBefore = doc.history().undoLabels().size();
        for (double dt : new double[]{0.05, 0.1, 0.2}) {
            UiAnimationClip from = c;
            assertTrue(doc.execute(AnimationCommands.editClip("Move keys", "intro", "move",
                x -> ClipEdits.moveKeys(from, List.of(new KeyId(SLIDE, 0)), dt).clip())));
        }
        doc.endInteraction();
        assertEquals(undoBefore + 1, doc.history().undoLabels().size());
        assertEquals(0.2, ClipEdits.track(clip("intro"), SLIDE).keys().getFirst().time(), 1e-9);
        assertTrue(doc.undo());
        assertEquals(c, clip("intro"));
    }

    @Test
    void copyPasteAtThePlayheadAndKeyingTheShownValue() {
        authorIntro();
        TimelineSession s = new TimelineSession();
        s.selection.add(new KeyId(OPACITY, 0));
        s.selection.add(new KeyId(OPACITY, 0.5));
        assertEquals(2, s.copy(clip("intro")));
        assertTrue(doc.execute(AnimationCommands.editClip("Longer", "intro", null, c -> ClipEdits.setDuration(c, 2))));
        s.selectedTrack = OPACITY;
        s.setPlayhead(1);
        assertTrue(doc.execute(s.paste(clip("intro"))));
        List<AnimKey> keys = ClipEdits.track(clip("intro"), OPACITY).keys();
        assertEquals(List.of(0.0, 0.5, 1.0, 1.5), keys.stream().map(AnimKey::time).toList());
        assertEquals(UiEasing.EASE_OUT, keys.get(2).easing(), "easing travels with the key");

        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc.archive(), UiRuntimeContext.basic());
        ui.resolveStyles();
        s.setPlayhead(0.25);
        assertTrue(doc.execute(s.keyAtPlayhead(clip("intro"), SLIDE, ui)));
        assertEquals(15, ((UiValue.Num) ClipEdits.track(clip("intro"), SLIDE).keys().get(1).value()).value(), 1e-9,
            "the key holds what the track showed there (half-way 30 → 0)");
    }

    @Test
    void customCurvesSurviveMovesAndPastesAndTransitionsAreEditable() {
        authorIntro();
        com.openmason.engine.format.omui.UiBezier curve = new com.openmason.engine.format.omui.UiBezier(0.3, 1.4, 0.6, 1);
        KeyId first = new KeyId(OPACITY, 0);
        assertTrue(doc.execute(AnimationCommands.editClip("Curve", "intro", null, c -> ClipEdits.setCurve(c, first, curve))));
        ClipEdits.Moved moved = ClipEdits.moveKeys(clip("intro"), List.of(first), 0.1);
        assertEquals(curve, ClipEdits.track(moved.clip(), OPACITY).keys().getFirst().bezier(), "a move keeps the curve");
        TimelineSession s = new TimelineSession();
        s.selection.add(first);
        s.copy(clip("intro"));
        s.selectedTrack = SLIDE;
        s.setPlayhead(0.25);
        assertTrue(doc.execute(s.paste(clip("intro"))));
        assertEquals(curve, ClipEdits.track(clip("intro"), SLIDE).keys().get(1).bezier(), "and so does a paste");
        assertTrue(doc.execute(AnimationCommands.editClip("Ease", "intro", null,
            c -> ClipEdits.setEasing(c, first, UiEasing.EASE_IN))));
        assertNull(ClipEdits.track(clip("intro"), OPACITY).keys().getFirst().bezier(), "a named easing clears it");

        String sheet = doc.archive().styles().keySet().iterator().next();
        var rule = doc.archive().styles().get(sheet).rules().getFirst();
        assertTrue(doc.execute(com.openmason.main.systems.uiEditor.command.DocumentCommands.setRuleTransition(sheet, 0,
            "opacity", new com.openmason.engine.format.omui.UiStyleSheet.StyleTransition("opacity", 0.2,
                UiEasing.LINEAR, 0, curve, Map.of()))), doc.lastMessage());
        var after = doc.archive().styles().get(sheet).rules().getFirst();
        assertEquals(curve, after.transitions().stream().filter(t -> t.property().equals("opacity")).findFirst()
            .orElseThrow().bezier());
        assertEquals(rule.style(), after.style(), "declarations untouched");
        assertTrue(doc.execute(com.openmason.main.systems.uiEditor.command.DocumentCommands.setRuleTransition(sheet, 0,
            "opacity", null)));
        assertTrue(doc.archive().styles().get(sheet).rules().getFirst().transitions().stream()
            .noneMatch(t -> t.property().equals("opacity")));
    }

    @Test
    void scrubbingPosesTheRuntimeButNeverTheDocument() {
        authorIntro();
        doc.endInteraction();
        OmuiArchive before = doc.archive();
        int steps = doc.history().undoLabels().size();
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc.archive(), UiRuntimeContext.basic());
        ui.resolveStyles();
        TimelineScrubber scrub = new TimelineScrubber();
        scrub.attach(ui);
        scrub.show(clip("intro"), 0.25);
        ui.resolveStyles();
        assertEquals(15, ui.find("panel").computedStyle().number("translate-y", -1), 1e-6);
        scrub.show(clip("intro"), 0.5);
        ui.resolveStyles();
        assertEquals(0, ui.find("panel").computedStyle().number("translate-y", -1), 1e-6);
        assertEquals(before, doc.archive(), "scrubbing is not an edit");
        assertEquals(steps, doc.history().undoLabels().size());

        // An edit (fewer tracks) re-poses only what the clip still animates.
        assertTrue(doc.execute(AnimationCommands.editClip("Drop", "intro", null, c -> ClipEdits.removeTrack(c, SLIDE))));
        scrub.show(clip("intro"), 0.5);
        ui.resolveStyles();
        assertNull(ui.find("panel").animatedStyle("translate-y"), "the dropped track's pose was released");

        scrub.release();
        ui.resolveStyles();
        assertNull(ui.find("panel").animatedStyle("opacity"), "leaving the Timeline shows the authored state");
    }

    @Test
    void renamingAClipCarriesStateMachinesAndGraphNodes() {
        authorIntro();
        assertTrue(doc.execute(AnimationCommands.addStateMachine("screen", UiStateMachine.Driver.MANUAL, null)));
        UiStateMachine m = doc.archive().stateMachines().get("screen");
        assertTrue(doc.execute(AnimationCommands.putStateMachine(new UiStateMachine(m.id(), m.driver(), null, "normal",
            List.of(new UiStateMachine.MachineState("normal", "intro", Map.of())), List.of(), Map.of()), null)));
        assertTrue(doc.archive().manifest().requires().contains(UiFeatures.STATES), "the feature is declared for you");
        UiGraph g = new UiGraph("anim", List.of(), List.of(new UiGraph.GraphNode("p", "ui:anim.play", 1, 0, 0, Map.of(),
            Map.of("clip", UiValue.of("intro")), Map.of())), List.of(), List.of(), Map.of());
        doc.execute(com.openmason.main.systems.uiEditor.command.UiCommand.of("graph",
            ctx -> ctx.setDoc(ctx.doc().withGraph(g))));

        assertFalse(doc.execute(AnimationCommands.removeClip("intro")), "a state machine still plays it");
        assertFalse(doc.execute(AnimationCommands.renameClip("intro", "open")), "the fixture already has 'open'");
        assertTrue(doc.execute(AnimationCommands.renameClip("intro", "intro_v2")), doc.lastMessage());
        assertNull(clip("intro"));
        assertEquals("intro_v2", doc.archive().stateMachines().get("screen").state("normal").clip());
        assertEquals(UiValue.of("intro_v2"), doc.archive().graphs().get("anim").nodes().getFirst().props().get("clip"));
    }

    @Test
    void saveReopenAndExportKeepTargetsEasingAndTheTimelineView() throws Exception {
        UiDocumentService service = new UiDocumentService(new UiProjectContext(() -> project),
            new UiRecoveryService(recoveryDir));
        UiEditorDocument d = service.create(UiDocumentTemplates.MENU_SCREEN, "test:ui/screens/pause_anim", "Pause");
        doc = d;
        authorIntro();
        TimelineViewState view = new TimelineViewState();
        view.activeClip = "intro";
        view.clip("intro").zoom = 4;
        service.setEditorStamps(x -> Map.of(TimelineViewState.ENTRY, view.stamp(x.archive())));
        UiAnimationClip authored = clip("intro");
        assertNull(service.save(d));

        OmuiArchive back = OmuiReader.read(d.file()).archive();
        assertEquals(authored, back.animations().get("intro"), "targets, keys and easing survive the save");
        TimelineViewState restored = TimelineViewState.restore(back);
        assertEquals("intro", restored.activeClip);
        assertEquals(4, restored.clip("intro").zoom, 1e-6);

        UiDocumentService.ExportResult export = service.export(d, project.resolve("Exports/pause.sbui"),
            ExportMode.COLLECT_ALL);
        assertNull(export.error(), export.error() + " " + export.diagnostics());
        SbuiArchive sbui = SbuiReader.read(export.target(), SbuiReader.Options.RUNTIME).archive();
        assertEquals(authored, sbui.source().animations().get("intro"), "the game gets the same clip");
    }
}
