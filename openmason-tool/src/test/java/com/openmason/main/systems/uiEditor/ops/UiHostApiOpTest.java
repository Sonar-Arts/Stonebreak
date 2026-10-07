package com.openmason.main.systems.uiEditor.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code set_host_api} / {@code set_provider} (#297): an author can declare the host contracts a
 * screen reads data and calls actions under, in one undoable step, without hand-editing the manifest.
 */
class UiHostApiOpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UiEditorDocument doc;
    private OmuiArchive original;

    @BeforeEach
    void open() throws Exception {
        original = OmuiReader.read(UiOpBatchTest.PAUSE).archive();
        doc = new UiEditorDocument(original, UiOpBatchTest.PAUSE, UiEditorDocument.Origin.FILE, null);
    }

    private boolean run(String json) throws Exception {
        return doc.execute(UiOpBatch.parse(MAPPER.readTree(json)).command(UiOpBatch.Components.NONE));
    }

    private List<String> apis() {
        return doc.archive().manifest().hostApis().stream().map(h -> h.id() + "@" + h.version()).toList();
    }

    @Test
    void declaresReplacesAndRemovesInOneUndoStep() throws Exception {
        assertTrue(run("""
            {"ops":[{"op":"set_host_api","id":"stonebreak:network.resync","version":1},
                    {"op":"set_host_api","id":"stonebreak:session","version":2,"optional":true},
                    {"op":"set_provider","id":"stonebreak:item-icon","version":1}]}"""), () -> doc.lastMessage());
        assertTrue(apis().contains("stonebreak:network.resync@1"));
        UiManifest.HostRequirement session = doc.archive().manifest().hostApis().stream()
            .filter(h -> h.id().equals("stonebreak:session")).findFirst().orElseThrow();
        assertEquals(2, session.version(), "a re-declaration replaces the old version");
        assertTrue(session.optional());
        assertTrue(doc.archive().manifest().providers().stream().anyMatch(h -> h.id().equals("stonebreak:item-icon")));

        assertTrue(run("""
            {"ops":[{"op":"set_host_api","id":"stonebreak:network.resync","version":null}]}"""));
        assertFalse(apis().contains("stonebreak:network.resync@1"));

        assertTrue(doc.undo());
        assertTrue(doc.undo());
        assertEquals(original.manifest(), doc.archive().manifest(), "two batches, two undo steps");
    }

    @Test
    void badIdsVersionsAndMissingRemovalsLeaveTheDocumentAlone() throws Exception {
        assertThrows(UiOpException.class, () -> UiOpBatch.parse(MAPPER.readTree(
            "[{\"op\":\"set_host_api\",\"id\":\"stonebreak:session\",\"version\":0}]")));
        assertThrows(UiOpException.class, () -> UiOpBatch.parse(MAPPER.readTree(
            "[{\"op\":\"set_host_api\",\"id\":\"stonebreak:session\",\"version\":\"1\"}]")));
        assertFalse(run("[{\"op\":\"set_host_api\",\"id\":\"Not An Id\",\"version\":1}]"));
        assertFalse(run("[{\"op\":\"set_host_api\",\"id\":\"stonebreak:nothing\",\"version\":null}]"));
        assertEquals(original, doc.archive());
    }
}
