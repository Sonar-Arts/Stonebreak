package com.openmason.engine.format.sbo;

import com.openmason.engine.format.oma.OMAReader;
import com.openmason.engine.format.oma.ParsedAnimClip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SBO 1.6 per-state animation clips: export embedding, loop-mode resolution
 * (clip default / forced loop / play-once), manifest round-trip, and the
 * editor re-save path preserving the authored loop flag.
 */
class SBOAnimationRoundTripTest {

    @TempDir
    Path dir;

    /**
     * Minimal valid {@code .omanim} ZIP: manifest.json + one track file.
     * {@code loop} is the clip's own flag — the value LoopMode.CLIP_DEFAULT
     * resolves to.
     */
    private static byte[] buildClip(String name, float fps, float duration, boolean loop,
                                    String partId) throws IOException {
        String manifest = """
                {"version":"1.1","name":"%s","fps":%s,"duration":%s,"loop":%s,
                 "tracks":[{"partId":"%s","partName":"panel","dataFile":"track_0.json"}]}
                """.formatted(name, fps, duration, loop, partId);
        String track = """
                {"partId":"%s","keyframes":[
                  {"time":0.0,"position":[0,0,0],"rotation":[0,0,0],"scale":[1,1,1],"easing":"LINEAR"},
                  {"time":%s,"position":[0,0,0],"rotation":[0,90,0],"scale":[1,1,1],"easing":"EASE_OUT"}]}
                """.formatted(partId, duration);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("track_0.json"));
            zos.write(track.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private Path writeFile(String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private SBOFormat.ExportParameters doorParams(Path omoClosed, Path omoOpen, Path clipFile) {
        SBOFormat.ExportParameters params = new SBOFormat.ExportParameters();
        params.setObjectId("stonebreak:oak_door_test");
        params.setObjectName("Oak Door Test");
        params.setObjectType(SBOFormat.ObjectType.BLOCK);
        params.setObjectPack("default");
        params.setAuthor("test");
        params.setStatesEnabled(true);
        params.setStates(List.of(
                new SBOFormat.StateSpec("Closed", omoClosed.toString(),
                        clipFile.toString(), SBOFormat.LoopMode.CLIP_DEFAULT),
                new SBOFormat.StateSpec("Open", omoOpen.toString(),
                        clipFile.toString(), SBOFormat.LoopMode.ONCE)));
        params.setDefaultStateName("Closed");
        return params;
    }

    @Test
    void exportEmbedsClipsAndResolvesLoopModes() throws IOException {
        byte[] clipBytes = buildClip("door_swing", 24f, 1.5f, /* clip's own loop */ true, "door-panel-id");
        Path clipFile = writeFile("door_swing.omanim", clipBytes);
        Path omoClosed = writeFile("closed.omo", "fake-closed-omo".getBytes());
        Path omoOpen = writeFile("open.omo", "fake-open-omo".getBytes());

        SBOFormat.ExportParameters params = doorParams(omoClosed, omoOpen, clipFile);
        assertTrue(params.isValid(), params.getValidationError());

        String sboPath = dir.resolve("oak_door_test.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omoClosed, sboPath));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(sboPath));
        assertEquals(SBOFormat.FORMAT_VERSION, raw.manifest().version());
        assertTrue(raw.manifest().hasAnimations());

        SBOFormat.StateEntry closed = raw.manifest().states().get(0);
        SBOFormat.StateEntry open = raw.manifest().states().get(1);

        // CLIP_DEFAULT inherits the clip's own loop=true; ONCE overrides to false.
        assertTrue(closed.hasAnimation());
        assertTrue(closed.animation().loop());
        assertTrue(open.hasAnimation());
        assertFalse(open.animation().loop());

        // Metadata probed from the clip's inner manifest.
        assertEquals("door_swing", open.animation().clipName());
        assertEquals(1.5f, open.animation().duration(), 1e-5);
        assertEquals(24f, open.animation().fps(), 1e-5);
        assertEquals(List.of("door-panel-id"), open.animation().requiredParts());
        assertEquals(SBOFormat.stateClipPath("Open"), open.animation().filename());

        // Clip embedded verbatim, and the engine reader decodes it — the
        // exact path the game's SBOBlockBridge.getStateClip takes.
        assertArrayEquals(clipBytes, raw.stateClipBytes().get("Open"));
        ParsedAnimClip parsed = new OMAReader().read(raw.stateClipBytes().get("Open"));
        assertEquals(1, parsed.tracks().size());
        assertEquals("door-panel-id", parsed.tracks().get(0).partId());
    }

    @Test
    void editorResaveProbesMetadataButPreservesAuthoredLoop() throws IOException {
        byte[] clipBytes = buildClip("door_swing", 24f, 1.5f, true, "door-panel-id");
        Path clipFile = writeFile("door_swing.omanim", clipBytes);
        Path omoClosed = writeFile("closed.omo", "fake-closed-omo".getBytes());
        Path omoOpen = writeFile("open.omo", "fake-open-omo".getBytes());

        String sboPath = dir.resolve("door.sbo").toString();
        assertTrue(new SBOSerializer().export(doorParams(omoClosed, omoOpen, clipFile), omoClosed, sboPath));
        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(sboPath));

        // Editor flips Open's loop to true via a stub ref (metadata zeroed —
        // the serializer must re-probe everything except the loop flag).
        SBOFormat.StateEntry open = raw.manifest().states().get(1);
        List<SBOFormat.StateEntry> edited = new ArrayList<>(raw.manifest().states());
        edited.set(1, new SBOFormat.StateEntry(open.name(), open.filename(), open.model(), open.checksum(),
                new SBOFormat.AnimationRef(open.animation().filename(), "", null, 0f, 0f, true, List.of())));
        SBOFormat.Document editedDoc = new SBOFormat.Document(
                raw.manifest().version(), raw.manifest().objectId(), raw.manifest().objectName(),
                raw.manifest().objectType(), raw.manifest().objectPack(), raw.manifest().checksum(),
                raw.manifest().author(), raw.manifest().description(), raw.manifest().createdAt(),
                raw.manifest().omoFilename(), raw.manifest().textureFilename(),
                raw.manifest().gameProperties(), edited, raw.manifest().defaultStateName(),
                raw.manifest().recipes(), raw.manifest().smeltingRecipes(), raw.manifest().fuel(),
                raw.manifest().sounds());

        String resaved = dir.resolve("door2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(editedDoc, raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), resaved));

        SBOFormat.StateEntry open2 = new SBOParser().parseRaw(Path.of(resaved))
                .manifest().states().get(1);
        assertTrue(open2.animation().loop(), "authored loop flip must survive re-save");
        assertEquals(1.5f, open2.animation().duration(), 1e-5, "metadata re-probed from bytes");
        assertFalse(open2.animation().checksum().isBlank(), "checksum recomputed from bytes");
    }

    @Test
    void clipOnTextureOnlySboRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SBOFormat.Document(
                "1.6", "x:y", "X", "item", "p", "c", "a", null, null,
                null, "texture.omt", null,
                List.of(new SBOFormat.StateEntry("s", "states/s/texture.omt", false, "c",
                        new SBOFormat.AnimationRef("states/s/clip.omanim", "", null, 0, 0, true, null))),
                "s", null, null, null, null));
    }

    @Test
    void singleAnimatedStateIsValidButSingleStaticStateIsNot() throws IOException {
        byte[] clipBytes = buildClip("spin", 24f, 1f, true, "blade");
        Path clipFile = writeFile("spin.omanim", clipBytes);
        Path omo = writeFile("fan.omo", "fake-omo".getBytes());

        SBOFormat.ExportParameters animated = new SBOFormat.ExportParameters();
        animated.setObjectId("stonebreak:fan");
        animated.setObjectName("Fan");
        animated.setObjectPack("default");
        animated.setAuthor("test");
        animated.setStatesEnabled(true);
        animated.setStates(List.of(new SBOFormat.StateSpec("Idle", omo.toString(),
                clipFile.toString(), SBOFormat.LoopMode.LOOP)));
        animated.setDefaultStateName("Idle");
        assertTrue(animated.isValid(), animated.getValidationError());

        SBOFormat.ExportParameters staticOne = new SBOFormat.ExportParameters();
        staticOne.setObjectId("stonebreak:x");
        staticOne.setObjectName("X");
        staticOne.setObjectPack("default");
        staticOne.setAuthor("test");
        staticOne.setStatesEnabled(true);
        staticOne.setStates(List.of(new SBOFormat.StateSpec("Idle", omo.toString())));
        staticOne.setDefaultStateName("Idle");
        assertFalse(staticOne.isValid());
    }

    @Test
    void loopModeResolution() {
        assertTrue(SBOFormat.LoopMode.CLIP_DEFAULT.resolve(true));
        assertFalse(SBOFormat.LoopMode.CLIP_DEFAULT.resolve(false));
        assertTrue(SBOFormat.LoopMode.LOOP.resolve(false));
        assertFalse(SBOFormat.LoopMode.ONCE.resolve(true));
    }

    @Test
    void statelessSboStillRoundTripsWithNoAnimations() throws IOException {
        Path omo = writeFile("plain.omo", "fake-omo".getBytes());
        SBOFormat.ExportParameters params = new SBOFormat.ExportParameters();
        params.setObjectId("stonebreak:plain");
        params.setObjectName("Plain");
        params.setObjectPack("default");
        params.setAuthor("test");

        String sboPath = dir.resolve("plain.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, sboPath));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(sboPath));
        assertFalse(raw.manifest().hasAnimations());
        assertTrue(raw.stateClipBytes().isEmpty());
        assertNotNull(raw.defaultBytes());
    }
}
