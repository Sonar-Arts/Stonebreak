package com.openmason.main.systems.menus.animationEditor.io;

import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.engine.format.oma.OMAReader;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-module format guard: a clip written by the tool's serializer must
 * decode identically through BOTH the tool's deserializer and the engine's
 * runtime reader, layer metadata included. Also pins v1.0 backward
 * compatibility (no {@code layer} block → full-body BASE defaults).
 */
class OmaRoundTripTest {

    @TempDir
    Path tempDir;

    private static AnimationClip overlayClip() {
        AnimationClip clip = new AnimationClip("attack", 24f, 0.8f, false, "player.omo");
        clip.setLayerType(AnimLayerMeta.LayerType.OVERLAY);
        clip.setMaskParts(List.of("leftArm", "rightArm"));
        clip.setFadeInSeconds(0.15f);
        clip.setFadeOutSeconds(0.25f);
        clip.setLayerPriority(3);

        Track track = clip.ensureTrack("part-uuid-1");
        track.setPartNameHint("rightArm");
        track.upsert(new Keyframe(0f, new Vector3f(), new Vector3f(), new Vector3f(1, 1, 1), Easing.LINEAR));
        track.upsert(new Keyframe(0.8f, new Vector3f(0, 1, 0), new Vector3f(0, 90, 0),
                new Vector3f(1, 1, 1), Easing.EASE_OUT));
        return clip;
    }

    @Test
    void layerMetadataRoundTripsThroughToolDeserializer() {
        String path = tempDir.resolve("attack.omanim").toString();
        assertTrue(new OMASerializer().save(overlayClip(), path, id -> "rightArm"));

        AnimationClip loaded = new OMADeserializer().load(path);
        assertNotNull(loaded);
        assertEquals(AnimLayerMeta.LayerType.OVERLAY, loaded.layerType());
        assertEquals(List.of("leftArm", "rightArm"), loaded.maskParts());
        assertEquals(0.15f, loaded.fadeInSeconds(), 1e-5f);
        assertEquals(0.25f, loaded.fadeOutSeconds(), 1e-5f);
        assertEquals(3, loaded.layerPriority());
        assertEquals(2, loaded.trackFor("part-uuid-1").size());
        assertEquals(Easing.EASE_OUT, loaded.trackFor("part-uuid-1").get(1).easing());
    }

    @Test
    void layerMetadataRoundTripsThroughEngineReader() throws IOException {
        Path file = tempDir.resolve("attack.omanim");
        assertTrue(new OMASerializer().save(overlayClip(), file.toString(), id -> "rightArm"));

        ParsedAnimClip parsed = new OMAReader().read(Files.readAllBytes(file));
        assertEquals("attack", parsed.name());
        assertEquals(AnimLayerMeta.LayerType.OVERLAY, parsed.layer().type());
        assertEquals(List.of("leftArm", "rightArm"), parsed.layer().maskParts());
        assertEquals(0.15f, parsed.layer().fadeInSeconds(), 1e-5f);
        assertEquals(0.25f, parsed.layer().fadeOutSeconds(), 1e-5f);
        assertEquals(3, parsed.layer().priority());
        assertTrue(parsed.layer().masksPart("any-id", "RIGHTARM"));
        assertEquals(1, parsed.tracks().size());
        assertEquals("rightArm", parsed.tracks().get(0).partName());
    }

    @Test
    void v10FileWithoutLayerBlockDefaultsToBaseInBothReaders() throws IOException {
        Path file = tempDir.resolve("legacy.omanim");
        writeV10Archive(file);

        // Engine reader
        ParsedAnimClip parsed = new OMAReader().read(Files.readAllBytes(file));
        assertEquals(AnimLayerMeta.LayerType.BASE, parsed.layer().type());
        assertTrue(parsed.layer().maskParts().isEmpty());
        assertEquals(AnimLayerMeta.DEFAULT_FADE_SECONDS, parsed.layer().fadeInSeconds(), 1e-5f);
        assertTrue(parsed.layer().masksPart("anything", "anything"));

        // Tool deserializer
        AnimationClip loaded = new OMADeserializer().load(file.toString());
        assertNotNull(loaded);
        assertEquals(AnimLayerMeta.LayerType.BASE, loaded.layerType());
        assertTrue(loaded.maskParts().isEmpty());
        assertEquals(AnimLayerMeta.DEFAULT_FADE_SECONDS, loaded.fadeInSeconds(), 1e-5f);
        assertEquals(1, loaded.trackFor("p1").size());
    }

    /** Hand-built v1.0 archive: manifest without a {@code layer} field. */
    private static void writeV10Archive(Path file) throws IOException {
        String manifest = """
                {
                  "version": "1.0",
                  "name": "legacy",
                  "fps": 30.0,
                  "duration": 1.0,
                  "loop": true,
                  "modelRef": null,
                  "tracks": [{"partId": "p1", "partName": "body", "dataFile": "track_p1.json"}],
                  "requiredParts": ["p1"]
                }
                """;
        String track = """
                {
                  "partId": "p1",
                  "keyframes": [
                    {"time": 0.0, "position": [0,0,0], "rotation": [0,0,0], "scale": [1,1,1], "easing": "LINEAR"}
                  ]
                }
                """;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("track_p1.json"));
            zos.write(track.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
