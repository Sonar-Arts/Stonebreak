package com.stonebreak.fx.sbfx;

import com.openmason.engine.format.ext.FormatExtensions;
import com.openmason.engine.format.ext.ManifestJson;
import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import com.openmason.engine.rendering.particles.FxPresets;
import com.openmason.engine.rendering.particles.FxSoundCue;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.stonebreak.fx.content.FirstFxContent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SBFX container (Stonebreak#435): round trips, determinism and refusal of damaged archives. */
class SbfxCodecTest {

    @Test
    void everyFirstContentDocumentRoundTrips() throws IOException {
        for (SbfxDocument doc : FirstFxContent.all().values()) {
            SbfxDocument back = SbfxCodec.read(SbfxCodec.write(doc));
            assertEquals(doc, back, doc.objectId());
            assertEquals(doc.objectId(), back.objectId());
            assertEquals(doc.fxId(), back.system().name());
        }
    }

    @Test
    void engineFxPresetsRoundTrip() throws IOException {
        for (FxSystem system : FxPresets.ALL) {
            SbfxDocument doc = SbfxDocument.of(OmfxDocument.of(system));
            assertEquals(doc, SbfxCodec.read(SbfxCodec.write(doc)), system.name());
        }
    }

    @Test
    void embeddedSoundsAndGameDataSurvive() throws IOException {
        byte[] wav = "RIFF....WAVEfmt fake sample".getBytes(StandardCharsets.US_ASCII);
        SoundDef def = new SoundDef("ignite", "sounds/ignite.wav", "", null, 0.8f, 1f, 1f, false);
        FxSystem system = FxSystem.of(com.openmason.engine.rendering.particles.ParticleLibrary.Presets.SMOKE, 0)
                .toBuilder().name("hearth")
                .sounds(new SoundData(List.of(def))).cue(FxSoundCue.onPlay("ignite")).build();
        FormatExtensions ext = ManifestJson.readExtensions(
                "{\"stonebreak:fx\":{\"category\":\"fire\",\"weights\":[1,2,3]}}", java.util.Set.of());
        OmfxDocument fx = new OmfxDocument("tester", system, List.of(), Map.of("sounds/ignite.wav", wav), ext, Map.of());
        SbfxDocument doc = new SbfxDocument("mymod:hearth", fx);

        SbfxDocument back = SbfxCodec.read(SbfxCodec.write(doc));

        assertEquals("mymod:hearth", back.objectId());
        assertEquals("tester", back.fx().author());
        assertArrayEquals(wav, back.fx().soundFile("sounds/ignite.wav"));
        assertEquals(ext, back.fx().extensions());
        // The engine fills the stub sample checksum on the first write; from then on it is a fixed point.
        assertEquals(back, SbfxCodec.read(SbfxCodec.write(back)));
    }

    @Test
    void writingIsDeterministic() throws IOException {
        SbfxDocument doc = FirstFxContent.all().get("SB_Leaf_Litter.sbfx");
        assertArrayEquals(SbfxCodec.write(doc), SbfxCodec.write(doc));
    }

    @Test
    void manifestNamesTheEnginePayload() throws IOException {
        Map<String, byte[]> entries = unzip(SbfxCodec.write(FirstFxContent.all().get("SB_Leaf_Litter.sbfx")));
        String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"format\" : \"stonebreak.fx\""), manifest);
        assertTrue(manifest.contains("\"objectId\" : \"stonebreak:leaf_litter\""), manifest);
        assertTrue(manifest.contains("\"engineFormat\" : \"openmason.fx\""), manifest);
        assertTrue(entries.containsKey("system.json"));
        assertTrue(entries.containsKey("textures/leaf.png"), "sprites travel inside: " + entries.keySet());
    }

    @Test
    void aTamperedPayloadIsRefused() throws IOException {
        Map<String, byte[]> entries = unzip(SbfxCodec.write(FirstFxContent.all().get("SB_Block_Debris.sbfx")));
        String system = new String(entries.get("system.json"), StandardCharsets.UTF_8);
        entries.put("system.json", system.replace("\"burst\" : 22", "\"burst\" : 220").getBytes(StandardCharsets.UTF_8));
        IOException e = assertThrows(IOException.class, () -> SbfxCodec.read(zip(entries)));
        assertTrue(e.getMessage().contains("checksum"), e.getMessage());
    }

    @Test
    void foreignOrNewerArchivesAreRefused() throws IOException {
        Map<String, byte[]> entries = unzip(SbfxCodec.write(FirstFxContent.all().get("SB_Torch_Flame.sbfx")));
        String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);

        entries.put("manifest.json", manifest.replace("\"version\" : 1", "\"version\" : 9").getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> SbfxCodec.read(zip(entries)));

        entries.put("manifest.json", manifest.replace("stonebreak.fx", "openmason.fx").getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> SbfxCodec.read(zip(entries)));

        entries.remove("manifest.json");
        assertThrows(IOException.class, () -> SbfxCodec.read(zip(entries)));

        assertThrows(IOException.class, () -> SbfxCodec.read(new byte[]{1, 2, 3}));
    }

    @Test
    void theManifestFxIdNamesTheSystem() throws IOException {
        // The checksum covers the payload, not the manifest: renaming the id is a rename, not damage.
        Map<String, byte[]> entries = unzip(SbfxCodec.write(FirstFxContent.all().get("SB_Torch_Flame.sbfx")));
        String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        entries.put("manifest.json", manifest.replace("\"fxId\" : \"torch_flame\"", "\"fxId\" : \"candle\"")
                .getBytes(StandardCharsets.UTF_8));
        SbfxDocument doc = SbfxCodec.read(zip(entries));
        assertEquals("candle", doc.system().name());
        assertEquals("stonebreak:torch_flame", doc.objectId());
    }

    @Test
    void objectIdsAreNamespaced() {
        assertEquals("stonebreak:torch_flame", SbfxDocument.objectIdFor("torch_flame"));
        assertEquals("stonebreak:my_fx_2", SbfxDocument.objectIdFor("My FX-2"));
        assertEquals("mymod:thing", SbfxDocument.objectIdFor("mymod:thing"));
        OmfxDocument fx = OmfxDocument.of(FxPresets.CAMPFIRE);
        assertThrows(IllegalArgumentException.class, () -> new SbfxDocument("campfire", fx));
        assertThrows(IllegalArgumentException.class, () -> new SbfxDocument("a:b:c", fx));
        assertThrows(IllegalArgumentException.class, () -> new SbfxDocument(":campfire", fx));
    }

    static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                out.put(e.getName(), zin.readAllBytes());
            }
        }
        return out;
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
