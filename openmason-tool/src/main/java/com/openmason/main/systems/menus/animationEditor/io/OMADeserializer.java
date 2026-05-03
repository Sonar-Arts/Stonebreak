package com.openmason.main.systems.menus.animationEditor.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads {@code .oma} ZIP archives produced by {@link OMASerializer}.
 */
public final class OMADeserializer {

    private static final Logger logger = LoggerFactory.getLogger(OMADeserializer.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnimationClip load(String filePath) {
        try (FileInputStream fis = new FileInputStream(filePath);
             ZipInputStream zis = new ZipInputStream(fis)) {

            Map<String, byte[]> entries = new HashMap<>();
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), readEntry(zis));
                zis.closeEntry();
            }

            byte[] manifestBytes = entries.get(OMAFormat.MANIFEST_FILENAME);
            if (manifestBytes == null) {
                logger.error("No manifest in .oma archive: {}", filePath);
                return null;
            }

            OMASerializer.ManifestDTO manifest =
                    objectMapper.readValue(manifestBytes, OMASerializer.ManifestDTO.class);

            AnimationClip clip = new AnimationClip(
                    manifest.name, manifest.fps, manifest.duration, manifest.loop, manifest.modelRef);

            if (manifest.tracks != null) {
                for (OMASerializer.TrackRef ref : manifest.tracks) {
                    byte[] trackBytes = entries.get(ref.dataFile);
                    if (trackBytes == null) {
                        logger.warn("Manifest references missing track file: {}", ref.dataFile);
                        continue;
                    }
                    OMASerializer.TrackDTO trackDto =
                            objectMapper.readValue(trackBytes, OMASerializer.TrackDTO.class);
                    Track track = clip.ensureTrack(ref.partId);
                    if (ref.partName != null && !ref.partName.isBlank()) {
                        track.setPartNameHint(ref.partName);
                    }
                    if (trackDto.keyframes != null) {
                        for (OMASerializer.KeyframeDTO kfDto : trackDto.keyframes) {
                            track.upsert(toKeyframe(kfDto));
                        }
                    }
                }
            }

            logger.info("Loaded animation clip '{}' ({} tracks) from {}",
                    clip.name(), clip.tracks().size(), filePath);
            return clip;
        } catch (IOException ex) {
            logger.error("Failed to load .oma file {}", filePath, ex);
            return null;
        }
    }

    private static byte[] readEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = zis.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static Keyframe toKeyframe(OMASerializer.KeyframeDTO dto) {
        return new Keyframe(
                dto.time,
                vec3(dto.position),
                vec3(dto.rotation),
                vec3OrOne(dto.scale),
                Easing.fromString(dto.easing)
        );
    }

    private static Vector3f vec3(float[] arr) {
        if (arr == null || arr.length < 3) return new Vector3f(0, 0, 0);
        return new Vector3f(arr[0], arr[1], arr[2]);
    }

    private static Vector3f vec3OrOne(float[] arr) {
        if (arr == null || arr.length < 3) return new Vector3f(1, 1, 1);
        return new Vector3f(arr[0], arr[1], arr[2]);
    }
}
