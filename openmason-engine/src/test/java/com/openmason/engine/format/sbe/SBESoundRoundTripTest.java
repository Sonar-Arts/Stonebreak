package com.openmason.engine.format.sbe;

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
 * Round-trip coverage for the SBE 1.4 {@code sounds[]} section. The SBE
 * parser (unlike the SBO one) hard-verifies checksums on {@code parse()}, so
 * a full parse passing implicitly proves the embedded sample checksums.
 */
class SBESoundRoundTripTest {

    @TempDir
    Path dir;

    private static final byte[] HONK_WAV = "RIFF-fake-honk-sample".getBytes();

    private Path writeFile(String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private SBEFormat.ExportParameters params() {
        SBEFormat.ExportParameters params = new SBEFormat.ExportParameters();
        params.setObjectId("test:goose");
        params.setObjectName("Goose");
        params.setEntityType(SBEFormat.EntityType.MOB);
        params.setObjectPack("test");
        params.setAuthor("junit");
        return params;
    }

    @Test
    void exportParseAndResavePreserveSounds() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        Path honkWav = writeFile("honk.wav", HONK_WAV);

        SBEFormat.ExportParameters params = params();
        params.setSounds(List.of(
                new SoundSpec("hurt", honkWav.toString(), null, 0.8f, 0.9f, 1.1f, true),
                new SoundSpec("step", null, "/sounds/GrassWalk.wav", 1.0f, 0.9f, 1.1f, false)));

        String out = dir.resolve("goose.sbe").toString();
        assertTrue(new SBESerializer().export(params, omo, out));

        // Full parse verifies checksums.
        SBEParser.ParsedSBE parsed = new SBEParser().parse(Path.of(out));
        assertEquals(SBEFormat.FORMAT_VERSION, parsed.document().version());
        assertTrue(parsed.document().hasSounds());

        List<SoundDef> defs = parsed.document().sounds().sounds();
        assertEquals(2, defs.size());
        SoundDef hurt = defs.get(0);
        assertTrue(hurt.isEmbedded());
        assertEquals("sounds/hurt_0.wav", hurt.filename());
        assertTrue(hurt.variation());
        assertArrayEquals(HONK_WAV, parsed.soundBytesFor("sounds/hurt_0.wav"),
                "embedded sample must survive verbatim");
        SoundDef step = defs.get(1);
        assertFalse(step.isEmbedded());
        assertEquals("/sounds/GrassWalk.wav", step.resourcePath());
        assertFalse(step.variation());

        // Editor-style round-trip: parseRaw's filename-keyed map feeds
        // exportFromDocument directly (sound bytes ride in the same map).
        SBEParser.RawParse raw = new SBEParser().parseRaw(Path.of(out));
        assertArrayEquals(HONK_WAV, raw.stateAssetBytes().get("sounds/hurt_0.wav"),
                "parseRaw must surface embedded sound bytes for the editor");

        String resaved = dir.resolve("goose2.sbe").toString();
        assertTrue(new SBESerializer().exportFromDocument(
                raw.manifest(), raw.omoBytes(), raw.stateAssetBytes(), resaved));

        SBEParser.ParsedSBE parsed2 = new SBEParser().parse(Path.of(resaved));
        assertEquals(defs, parsed2.document().sounds().sounds(),
                "sound defs must survive an editor round-trip");
        assertArrayEquals(HONK_WAV, parsed2.soundBytesFor("sounds/hurt_0.wav"));
    }

    @Test
    void resaveWithoutEmbeddedSoundBytesFails() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        Path honkWav = writeFile("honk.wav", HONK_WAV);

        SBEFormat.ExportParameters params = params();
        params.setSounds(List.of(new SoundSpec("hurt", honkWav.toString(), null, 1f, 1f, 1f, true)));
        String out = dir.resolve("goose.sbe").toString();
        assertTrue(new SBESerializer().export(params, omo, out));

        SBEParser.RawParse raw = new SBEParser().parseRaw(Path.of(out));
        assertFalse(new SBESerializer().exportFromDocument(
                        raw.manifest(), raw.omoBytes(), java.util.Map.of(),
                        dir.resolve("bad.sbe").toString()),
                "missing embedded sound bytes must refuse to save");
    }

    @Test
    void soundlessSbeStillRoundTripsWithNoSounds() throws IOException {
        Path omo = writeFile("model.omo", "fake-omo".getBytes());
        String out = dir.resolve("plain.sbe").toString();
        assertTrue(new SBESerializer().export(params(), omo, out));

        SBEParser.ParsedSBE parsed = new SBEParser().parse(Path.of(out));
        assertFalse(parsed.document().hasSounds());
        assertNull(parsed.document().sounds());
        assertTrue(parsed.soundBytes().isEmpty());

        SBEParser.RawParse raw = new SBEParser().parseRaw(Path.of(out));
        String resaved = dir.resolve("plain2.sbe").toString();
        assertTrue(new SBESerializer().exportFromDocument(
                raw.manifest(), raw.omoBytes(), raw.stateAssetBytes(), resaved));
        assertFalse(new SBEParser().parse(Path.of(resaved)).document().hasSounds());
    }
}
