package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionCall;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.EditPolicy;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.input.PointerEvent;
import com.openmason.engine.ui.runtime.input.UiInputRouter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.binding.BindingRig.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two-way settings edits through a real text field: draft, validation, apply and cancel (#289). */
class TwoWayBindingTest {

    private static final HostContract SETTINGS = HostContract.of("stonebreak:settings", 1);

    private final UiHost host = new UiHost();
    private final List<UiValue> saves = new ArrayList<>();
    private final DataCell settings;
    private UiDocumentInstance ui;
    private UiInputRouter router;
    private UiBinder binder;

    TwoWayBindingTest() {
        DataType.Obj type = DataType.object("name", DataType.string(), "fov", DataType.integer());
        settings = host.data().registerEditable("settings", new DataCell(type, obj("name", "Steve", "fov", 70)), SETTINGS,
            new EditPolicy("stonebreak:settings.apply", (path, value, draft) ->
                path.equals(DataPath.parse("settings.name")) && ((UiValue.Str) value).value().isBlank()
                    ? "name cannot be empty" : null));
        host.actions().register(ActionSpec.of("stonebreak:settings.apply", SETTINGS, DataType.object("value", type),
            DataType.ANY), (args, ctx) -> {
                saves.add(args.get("value"));
                settings.set(args.get("value"));
                return CompletableFuture.completedFuture(UiValue.NULL);
            });
    }

    private void open() {
        OmuiArchive doc = BindingRig.withData(screen("t:ui/settings", box("root").style("width", 400).style("height", 200)
            .data("settings").kids(
                node("name", "TextField").style("width", 200).style("height", 30)
                    .bind("prop:text", ".name", UiNode.BindingMode.TWO_WAY, null),
                node("echo", "Label").bind("prop:text", ".name"),
                node("other", "Box").style("width", 50).style("height", 30).prop("focusable", true))),
            "stonebreak:settings@1");
        ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT));
        ui.setMetrics(UiMetrics.of(400, 200, 1));
        binder = UiBinder.open(ui, host, UiConverters.NONE);
        router = new UiInputRouter(ui);
        frame();
    }

    private void frame() {
        ui.update();
        router.sync();
        ui.update();
    }

    private void typeInto(String key, String text, boolean commit) {
        UiElement f = ui.find(key);
        float x = f.rect().x() + 5;
        float y = f.rect().y() + 5;
        router.pointerDown(x, y, PointerEvent.PRIMARY, 0);
        router.pointerUp(x, y, PointerEvent.PRIMARY, 0);
        frame();
        router.keyDown(MKeys.KEY_A, MKeys.MOD_CONTROL, false);
        router.keyUp(MKeys.KEY_A, MKeys.MOD_CONTROL);
        text.codePoints().forEach(router::text);
        frame();
        if (commit) {
            router.keyDown(MKeys.KEY_ENTER, 0, false);
            router.keyUp(MKeys.KEY_ENTER, 0);
            frame();
        }
    }

    @Test
    void keystrokesStayLocalUntilCommitAndNothingSavesBeforeApply() {
        open();
        assertEquals("Steve", ui.find("name").text("text"));
        typeInto("name", "Alex", false);
        assertEquals("Alex", ui.find("name").text("text"), "the field shows what is typed");
        assertEquals("Steve", ui.find("echo").text("text"), "typing does not reach the draft");
        assertFalse(binder.scope().edits().isDirty());

        router.keyDown(MKeys.KEY_ENTER, 0, false);
        router.keyUp(MKeys.KEY_ENTER, 0);
        frame();
        assertEquals("Alex", ui.find("echo").text("text"), "commit staged the draft; bound elements read it");
        assertTrue(binder.scope().edits().isDirty());
        assertTrue(saves.isEmpty(), "nothing is saved until apply");

        List<ActionCall> calls = binder.applyEdits("apply");
        assertEquals(ActionCall.State.SUCCEEDED, calls.getFirst().state());
        assertEquals(List.of(obj("name", "Alex", "fov", 70)), saves);
        assertFalse(binder.scope().edits().isDirty());
        assertEquals("Alex", ui.find("name").text("text"));
        binder.close();
    }

    @Test
    void invalidCommitsAreRejectedAndCancelRestoresEverything() {
        open();
        typeInto("name", " ", true);
        assertTrue(ui.find("name").hasState("invalid"), "the host validator refused it");
        assertEquals(" ", ui.find("name").text("text"), "the input stays for the user to fix");
        assertFalse(binder.scope().edits().isDirty());

        typeInto("name", "Alex", true);
        assertFalse(ui.find("name").hasState("invalid"));
        assertTrue(binder.scope().edits().isDirty());
        binder.revertEdits();
        frame();
        assertEquals("Steve", ui.find("name").text("text"));
        assertEquals("Steve", ui.find("echo").text("text"));
        assertFalse(binder.scope().edits().isDirty());
        assertTrue(saves.isEmpty());
        binder.close();
    }

    @Test
    void closingWithUnappliedEditsDropsThem() {
        open();
        typeInto("name", "Alex", true);
        binder.close();
        assertTrue(saves.isEmpty());
        assertEquals(obj("name", "Steve", "fov", 70), settings.value());
    }
}
