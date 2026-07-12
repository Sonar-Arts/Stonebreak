package com.openmason.engine.format.sbo;

import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import com.openmason.engine.format.sound.SoundSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip coverage for the SBO 1.7 {@code sounds[]} section: export with
 * embedded and resource-referenced defs, re-parse, editor-style re-save, and
 * back-compat for soundless files. Mirrors {@link SBOAnimationRoundTripTest}.
 */
class SBOSoundRoundTripTest {

    @TempDir
    Path dir;

    private static final byte[] BREAK_WAV = "RIFF-fake-break-sample".getBytes();
    private static final byte[] CRACK_WAV = "RIFF-fake-crack-sample".getBytes();

    private Path writeFile(String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private SBOFormat.ExportParameters params() {
        SBOFormat.ExportParameters params = new SBOFormat.ExportParameters();
        params.setObjectId("test:grass");
        params.setObjectName("Grass");
        params.setObjectType(SBOFormat.ObjectType.BLOCK);
        params.setObjectPack("test");
        params.setAuthor("junit");
        return params;
    }

    @Test
    void exportEmbedsAndReferencesSounds() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        Path breakWav = writeFile("break.wav", BREAK_WAV);
        Path crackWav = writeFile("crack.wav", CRACK_WAV);

        SBOFormat.ExportParameters params = params();
        params.setSounds(List.of(
                new SoundSpec("break", breakWav.toString(), null, 0.9f, 0.75f, 0.85f, true),
                new SoundSpec("break", crackWav.toString(), null, 0.9f, 0.75f, 0.85f, false),
                new SoundSpec("step", null, "/sounds/GrassWalk.wav", 1.0f, 0.9f, 1.1f, true)));

        String out = dir.resolve("grass.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        assertEquals(SBOFormat.FORMAT_VERSION, raw.manifest().version());
        assertTrue(raw.manifest().hasSounds());

        List<SoundDef> defs = raw.manifest().sounds().sounds();
        assertEquals(3, defs.size());

        SoundDef break0 = defs.get(0);
        assertTrue(break0.isEmbedded());
        assertEquals("sounds/break_0.wav", break0.filename());
        assertFalse(break0.checksum().isBlank());
        assertArrayEquals(BREAK_WAV, raw.soundBytes().get("sounds/break_0.wav"),
                "embedded sample must survive verbatim");

        assertTrue(break0.variation(), "authored variation switch survives");

        SoundDef break1 = defs.get(1);
        assertEquals("sounds/break_1.wav", break1.filename(),
                "same-event samples must get distinct entry paths");
        assertFalse(break1.variation(), "per-def variation is independent");
        assertArrayEquals(CRACK_WAV, raw.soundBytes().get("sounds/break_1.wav"));

        SoundDef step = defs.get(2);
        assertFalse(step.isEmbedded());
        assertEquals("/sounds/GrassWalk.wav", step.resourcePath());
        assertEquals(1.0f, step.volume(), 1e-6);
        assertEquals(0.9f, step.pitchMin(), 1e-6);
        assertEquals(1.1f, step.pitchMax(), 1e-6);
        assertEquals(2, raw.soundBytes().size(), "resource refs embed nothing");

        assertEquals(List.of("break", "step"),
                List.copyOf(raw.manifest().sounds().events()));
        assertEquals(2, raw.manifest().sounds().defsFor("break").size());
    }

    @Test
    void editorResavePreservesSounds() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        Path breakWav = writeFile("break.wav", BREAK_WAV);

        SBOFormat.ExportParameters params = params();
        params.setSounds(List.of(
                new SoundSpec("break", breakWav.toString(), null, 0.9f, 0.75f, 0.85f, true),
                new SoundSpec("step", null, "/sounds/GrassWalk.wav", 1.0f, 0.9f, 1.1f, false)));
        String out = dir.resolve("grass.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        String resaved = dir.resolve("grass2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), raw.soundBytes(), resaved));

        SBOParser.RawParse raw2 = new SBOParser().parseRaw(Path.of(resaved));
        assertEquals(raw.manifest().sounds().sounds(), raw2.manifest().sounds().sounds(),
                "sound defs must survive an editor round-trip byte-for-byte");
        assertArrayEquals(BREAK_WAV, raw2.soundBytes().get("sounds/break_0.wav"));
    }

    @Test
    void resaveWithoutEmbeddedBytesFails() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        Path breakWav = writeFile("break.wav", BREAK_WAV);

        SBOFormat.ExportParameters params = params();
        params.setSounds(List.of(new SoundSpec("break", breakWav.toString(), null, 1f, 1f, 1f, true)));
        String out = dir.resolve("grass.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        // Legacy overload carries no sound bytes — must refuse rather than
        // write a manifest that references a missing entry.
        assertFalse(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), dir.resolve("bad.sbo").toString()));
    }

    @Test
    void resourceOnlySoundsNeedNoBytes() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());

        SBOFormat.ExportParameters params = params();
        params.setSounds(List.of(new SoundSpec("step", null, "/sounds/GrassWalk.wav", 1f, 0.9f, 1.1f, true)));
        String out = dir.resolve("grass.sbo").toString();
        assertTrue(new SBOSerializer().export(params, omo, out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        String resaved = dir.resolve("grass2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                        raw.stateBytes(), raw.stateClipBytes(), resaved),
                "resource-referenced sounds round-trip through the legacy overload");
        assertTrue(new SBOParser().parseRaw(Path.of(resaved)).manifest().hasSounds());
    }

    @Test
    void soundlessSboStillRoundTripsWithNoSounds() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        String out = dir.resolve("plain.sbo").toString();
        assertTrue(new SBOSerializer().export(params(), omo, out));

        SBOParser.RawParse raw = new SBOParser().parseRaw(Path.of(out));
        assertFalse(raw.manifest().hasSounds());
        assertNull(raw.manifest().sounds());
        assertTrue(raw.soundBytes().isEmpty());

        String resaved = dir.resolve("plain2.sbo").toString();
        assertTrue(new SBOSerializer().exportFromDocument(raw.manifest(), raw.defaultBytes(),
                raw.stateBytes(), raw.stateClipBytes(), resaved));
        assertFalse(new SBOParser().parseRaw(Path.of(resaved)).manifest().hasSounds());
    }

    @Test
    void defAndSpecValidation() {
        // exactly one source
        assertThrows(IllegalArgumentException.class,
                () -> new SoundDef("step", "sounds/a.wav", "c", "/sounds/a.wav", 1f, 1f, 1f, true));
        assertThrows(IllegalArgumentException.class,
                () -> new SoundDef("step", null, null, null, 1f, 1f, 1f, true));
        assertThrows(IllegalArgumentException.class,
                () -> new SoundSpec("step", "/tmp/a.wav", "/sounds/a.wav", 1f, 1f, 1f, true));
        // pitch range and volume sanity
        assertThrows(IllegalArgumentException.class,
                () -> new SoundDef("step", null, null, "/sounds/a.wav", 0f, 1f, 1f, true));
        assertThrows(IllegalArgumentException.class,
                () -> new SoundDef("step", null, null, "/sounds/a.wav", 1f, 1.2f, 0.8f, true));
        // blank event
        assertThrows(IllegalArgumentException.class,
                () -> new SoundDef(" ", null, null, "/sounds/a.wav", 1f, 1f, 1f, true));
        // valid defs construct
        assertDoesNotThrow(() -> new SoundData(List.of(
                new SoundDef("custom_event", null, null, "/sounds/a.wav", 0.5f, 0.9f, 1.1f, false))));
    }
}
