package com.openmason.main.systems.scripting.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.engine.format.oma.OMAReader;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.openmason.main.systems.scripting.doc.HeadlessModelDocument;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimCommandsTest {

    @TempDir
    Path tmp;

    private HeadlessModelDocument doc;
    private ModelCommands cmds;

    @BeforeEach
    void setUp() {
        doc = new HeadlessModelDocument();
        cmds = new ModelCommands(doc, new ObjectMapper(), tmp);
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), new Vector3f(0, 3, 0), null, null);
    }

    @Test
    void keyDefaultsOmittedComponentsFromPartTransform() {
        cmds.anim().createClip("idle", 2.0f, 30f, true);
        // Only rotation supplied — position must come from the part's transform (0,3,0).
        cmds.anim().key(null, "body", 0.5f, null, new Vector3f(0, 10, 0), null, null);

        AnimCommands.ClipInfo info = cmds.anim().info(null);
        assertEquals(1, info.tracks());
        assertEquals(1, info.keyframes());

        cmds.anim().save(null, "idle.omanim");
        List<String> files = cmds.anim().flushSaves();
        ParsedAnimClip parsed = readClip(files.get(0));
        ParsedAnimTrack track = parsed.tracks().get(0);
        assertEquals("body", track.partName(), "track must carry the part-name binding hint");
        var kf = track.keyframes().get(0);
        assertEquals(3f, kf.position().y, 1e-5, "omitted position defaults to the part transform");
        assertEquals(10f, kf.rotation().y, 1e-5);
        assertEquals(1f, kf.scale().x, 1e-5);
    }

    @Test
    void saveCreatesMissingDirectoriesAndAddsExtension() throws Exception {
        cmds.createPart("cube", "arm", null, null, null, null);
        cmds.anim().createClip("swing", 0.6f, 24f, false);
        cmds.anim().key(null, "arm", 0f, null, null, null, "ease_in_out");

        cmds.anim().save("swing", "sub/swing"); // extension added, dirs created at flush
        List<String> files = cmds.anim().flushSaves();
        Path written = Path.of(files.get(0));
        assertTrue(written.toString().endsWith(".omanim"));
        assertTrue(Files.exists(written));
        assertEquals(tmp.resolve("sub").resolve("swing.omanim").normalize(), written.normalize());
    }

    @Test
    void layerMetadataSerializes(@TempDir Path dir) throws Exception {
        ModelCommands local = new ModelCommands(doc, new ObjectMapper(), dir);
        local.createPart("cube", "arm", null, null, null, null);
        local.anim().createClip("swing", 0.6f, 24f, false);
        local.anim().key(null, "arm", 0.25f, null, null, null, "ease_in");
        local.anim().setLayer(null, "overlay", List.of("arm"), 0.1f, 0.2f, 3);
        local.anim().save(null, "swing.omanim");

        List<String> files = local.anim().flushSaves();
        assertTrue(Files.size(Path.of(files.get(0))) > 0);
        ParsedAnimClip parsed = readClip(files.get(0));
        assertEquals("swing", parsed.name());
        assertEquals(24f, parsed.fps(), 1e-5);
        assertEquals(0.6f, parsed.duration(), 1e-5);
        assertFalse(parsed.loop());
        AnimLayerMeta layer = parsed.layer();
        assertNotNull(layer);
        assertEquals(AnimLayerMeta.LayerType.OVERLAY, layer.type());
        assertEquals("EASE_IN", parsed.tracks().get(0).keyframes().get(0).easing());
    }

    @Test
    void teachingErrors() {
        // No clip yet.
        assertThrows(CommandException.class,
                () -> cmds.anim().key(null, "body", 0f, null, null, null, null));

        cmds.anim().createClip("idle", 1f, 30f, true);
        // Duplicate clip name.
        assertThrows(CommandException.class, () -> cmds.anim().createClip("idle", null, null, null));
        // Unknown part (with known-name list).
        CommandException noPart = assertThrows(CommandException.class,
                () -> cmds.anim().key(null, "bodz", 0f, null, null, null, null));
        assertTrue(noPart.getMessage().contains("body"));
        // Bad easing.
        CommandException easing = assertThrows(CommandException.class,
                () -> cmds.anim().key(null, "body", 0f, null, null, null, "smoothstep"));
        assertTrue(easing.hint().contains("ease_in_out"));
        // Time past duration.
        CommandException late = assertThrows(CommandException.class,
                () -> cmds.anim().key(null, "body", 5f, null, null, null, null));
        assertTrue(late.hint().contains("duration"));
        // Bad layer type; unknown mask part.
        assertThrows(CommandException.class,
                () -> cmds.anim().setLayer(null, "additive", null, null, null, null));
        assertThrows(CommandException.class,
                () -> cmds.anim().setLayer(null, null, List.of("nope"), null, null, null));
        // Empty clip save.
        assertThrows(CommandException.class, () -> cmds.anim().save(null, "x.omanim"));
        // Unknown clip reference.
        assertThrows(CommandException.class, () -> cmds.anim().info("walk"));
    }

    @Test
    void relativeSaveWithoutBaseDirFailsEarly() {
        ModelCommands live = new ModelCommands(doc, new ObjectMapper()); // no baseDir (live mode)
        live.anim().createClip("idle", 1f, 30f, true);
        live.anim().key(null, "body", 0f, null, null, null, null);
        CommandException e = assertThrows(CommandException.class,
                () -> live.anim().save(null, "idle.omanim"));
        assertTrue(e.hint().contains("absolute"));
    }

    @Test
    void savesAreDeferredUntilFlush() throws Exception {
        cmds.anim().createClip("idle", 1f, 30f, true);
        cmds.anim().key(null, "body", 0f, null, null, null, null);
        cmds.anim().save(null, "idle.omanim");
        assertFalse(Files.exists(tmp.resolve("idle.omanim")),
                "save() must only queue — a failing script writes nothing");
        cmds.anim().flushSaves();
        assertTrue(Files.exists(tmp.resolve("idle.omanim")));
    }

    @Test
    void animOpsAreTraced() {
        cmds.anim().createClip("idle", 2f, 30f, true);
        cmds.anim().key(null, "body", 0f, null, null, null, null);
        cmds.anim().save(null, "idle.omanim");
        List<String> ops = cmds.opsTrace().stream().map(n -> n.get("op").asText()).toList();
        assertEquals(List.of("create_part", "anim_clip", "anim_key", "anim_save"), ops);
    }

    private static ParsedAnimClip readClip(String path) {
        try {
            return new OMAReader().read(Files.readAllBytes(Path.of(path)));
        } catch (Exception e) {
            throw new AssertionError("written .omanim must parse with the engine reader", e);
        }
    }
}
