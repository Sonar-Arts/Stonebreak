package com.openmason.engine.ui.runtime.access;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiValidator;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.format.sbui.SbuiWriter;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.input.InputDevice;
import com.openmason.engine.ui.runtime.input.UiActionMap;
import com.openmason.engine.ui.runtime.input.UiInputRouter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.rule;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.UiDocs.sheet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Semantic metadata (#288): roles, names, values, states, hints and status survive OMUI and
 * SBUI serialization, need the {@code ui-input} feature, and build the accessibility tree.
 */
class AccessibilityMetadataTest {

    private static OmuiArchive dialog(boolean declare) {
        UiStyleSheet s = sheet("s", rule("Button:focus-visible", "border-color", "#FFCC55"),
            rule("TextField:invalid", "border-color", "#FF0000"));
        OmuiArchive doc = screen("t:ui/a11y", box("root").style("width", 400).style("height", 300).kids(
            box("dialog").prop("focusScope", "modal").prop("accessibleName", "Rename world").kids(
                label("title", "Rename"),
                node("name", "TextField").prop("placeholder", "World name").prop("accessibleDescription",
                    "Letters and digits only").prop("pattern", "[A-Za-z0-9]+").prop("status", "warning"),
                node("save", "Button").prop("actionHints", UiValue.Obj.sorted(Map.of(
                    "ui.submit", UiValue.of("Save")))).kids(label("save_text", "Save world")),
                node("art", "Image").prop("role", "none"),
                node("pw", "TextField").prop("password", true).prop("text", "hunter2"))), s);
        return declare ? withRequires(doc, List.of(UiFeatures.INPUT)) : doc;
    }

    private static OmuiArchive withRequires(OmuiArchive doc, List<String> req) {
        UiManifest m = doc.manifest();
        return doc.withManifest(new UiManifest(m.schemaVersion(), m.documentId(), m.kind(), m.displayName(), m.uiApi(),
            m.layoutSemantics(), req, m.hostApis(), m.providers(), m.unknown()));
    }

    @Test
    void inputMetadataNeedsTheFeatureDeclared() throws Exception {
        List<UiDiagnostic> problems = OmuiValidator.validate(dialog(false));
        List<String> pointers = new ArrayList<>();
        for (UiDiagnostic d : problems) {
            if (d.code() == UiDiagnostic.Code.UNDECLARED_FEATURE) {
                pointers.add(d.pointer());
            }
        }
        assertTrue(pointers.contains("/root/children/0/props/focusScope"), pointers.toString());
        assertTrue(pointers.contains("/root/children/0/children/1/type"), "TextField is a ui-input widget");
        assertTrue(pointers.contains("/rules/0/selector"), ":focus-visible needs the feature");
        // The writer infers the feature (#282 hardening) rather than refusing: the saved bytes declare it.
        assertTrue(OmuiReader.read(OmuiWriter.write(dialog(false))).archive().manifest().requires()
            .contains(UiFeatures.INPUT), "the writer declares what the document uses");
        assertTrue(OmuiValidator.validate(dialog(true)).stream().noneMatch(UiDiagnostic::isError));
    }

    @Test
    void localizationKeysNeedTheirOwnFeature() {
        OmuiArchive doc = screen("t:ui/l10n", box("root").kids(node("t", "Label").prop("textKey", "ui.hello")));
        assertTrue(OmuiValidator.validate(doc).stream().anyMatch(d -> d.code() == UiDiagnostic.Code.UNDECLARED_FEATURE));
        assertTrue(OmuiValidator.validate(withRequires(doc, List.of(UiFeatures.L10N))).stream()
            .noneMatch(UiDiagnostic::isError));
    }

    @Test
    void metadataSurvivesOmuiAndSbuiRoundTrips() throws Exception {
        OmuiArchive doc = dialog(true);
        OmuiArchive back = OmuiReader.read(OmuiWriter.write(doc)).archive();
        assertEquals(doc.document().root(), back.document().root(), "OMUI keeps every input/a11y property");
        assertEquals(doc.styles(), back.styles(), "and the input pseudo-states");
        SbuiArchive sbui = SbuiExporter.export(doc, SbuiExporter.Options.shared()).archive();
        SbuiArchive read = SbuiReader.read(SbuiWriter.write(sbui), SbuiReader.Options.RUNTIME).archive();
        UiNode dialog = read.source().document().root().children().getFirst();
        assertEquals(UiValue.of("Rename world"), dialog.props().get("accessibleName"));
        assertEquals(UiValue.of("modal"), dialog.props().get("focusScope"));
        assertEquals(doc.document().root(), read.source().document().root(), "the game reads what the editor wrote");
    }

    @Test
    void theTreeExposesRolesNamesValuesAndStates() {
        try (UiDocumentInstance ui = UiDocumentInstance.instantiate(dialog(true),
            UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT))) {
            ui.setMetrics(UiMetrics.of(400, 300, 1));
            ui.update();
            UiInputRouter router = new UiInputRouter(ui);
            router.sync();
            ui.update();
            AccessibleNode root = AccessibilityTree.snapshot(ui, UiActionMap.defaults(), InputDevice.KEYBOARD);
            AccessibleNode dialog = find(root, "dialog");
            assertEquals("dialog", dialog.role());
            assertEquals("Rename world", dialog.name());
            assertTrue(dialog.states().contains("modal"));
            AccessibleNode name = find(root, "name");
            assertEquals("text-field", name.role());
            assertEquals("World name", name.name(), "a field without a name is named by its placeholder");
            assertEquals("Letters and digits only", name.description());
            assertTrue(name.states().contains("status:warning"), "status is a state, not only a colour");
            assertTrue(name.states().contains("focused"), "the modal focused its first field");
            AccessibleNode save = find(root, "save");
            assertEquals("Save world", save.name(), "a button is named by its labels");
            assertEquals(List.of("Enter: Save"), save.hints());
            assertEquals("*******", find(root, "pw").value(), "passwords are masked in the tree too");
            assertTrue(findOrNull(root, "art") == null, "role none without a name is transparent");
            assertEquals("text", find(root, "title").role());
        }
    }

    @Test
    void invalidAndDisabledAreExposed() {
        try (UiDocumentInstance ui = UiDocumentInstance.instantiate(dialog(true),
            UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT))) {
            ui.setMetrics(UiMetrics.of(400, 300, 1));
            ui.update();
            UiElement save = ui.find("save");
            save.setEnabled(false);
            ui.find("name").setState(UiElement.INVALID, true);
            ui.update();
            AccessibleNode root = AccessibilityTree.snapshot(ui, UiActionMap.defaults(), InputDevice.KEYBOARD);
            assertTrue(find(root, "save").states().contains("disabled"));
            assertTrue(find(root, "name").states().contains("invalid"));
            assertFalse(find(root, "save").states().contains("focused"));
            assertEquals(Set.of("focusable", "disabled"), find(root, "save").states());
        }
    }

    private static AccessibleNode find(AccessibleNode n, String key) {
        AccessibleNode f = findOrNull(n, key);
        if (f == null) {
            throw new AssertionError("no node " + key);
        }
        return f;
    }

    private static AccessibleNode findOrNull(AccessibleNode n, String key) {
        if (n.key().equals(key)) {
            return n;
        }
        for (AccessibleNode c : n.children()) {
            AccessibleNode f = findOrNull(c, key);
            if (f != null) {
                return f;
            }
        }
        return null;
    }
}
