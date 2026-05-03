package com.openmason.main.systems.menus.animationEditor.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes an {@link AnimationClip} to a {@code .oma} ZIP archive.
 *
 * <p>Mirrors {@code OMTSerializer} structurally — single
 * responsibility, Jackson for JSON, manifest + per-blob layout — so the two
 * editors share file-IO conventions.
 */
public final class OMASerializer {

    private static final Logger logger = LoggerFactory.getLogger(OMASerializer.class);

    private final ObjectMapper objectMapper;

    public OMASerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Serialize the clip to disk.
     *
     * @param clip     The clip to write
     * @param filePath Destination path; .oma extension auto-appended
     * @return true on success
     */
    public boolean save(AnimationClip clip, String filePath) {
        return save(clip, filePath, null);
    }

    /**
     * Save with an optional partId → partName lookup so each track ref can
     * carry the target part's display name. Lets the loader fall back to a
     * name match when partIds drift across model save/load cycles.
     */
    public boolean save(AnimationClip clip, String filePath,
                        java.util.function.Function<String, String> partNameLookup) {
        if (clip == null || filePath == null) {
            return false;
        }
        String resolved = OMAFormat.ensureExtension(filePath);

        try (FileOutputStream fos = new FileOutputStream(resolved);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            List<TrackRef> trackRefs = new ArrayList<>();
            for (Track track : clip.tracks().values()) {
                String filename = OMAFormat.trackFilename(track.partId());
                String partName = partNameLookup != null ? partNameLookup.apply(track.partId()) : null;
                trackRefs.add(new TrackRef(track.partId(), partName, filename));
                writeTrack(zos, filename, track);
            }

            writeManifest(zos, clip, trackRefs);

            logger.info("Saved animation clip '{}' ({} tracks) to {}",
                    clip.name(), clip.tracks().size(), resolved);
            return true;
        } catch (IOException ex) {
            logger.error("Failed to save .oma to {}", resolved, ex);
            return false;
        }
    }

    private void writeManifest(ZipOutputStream zos, AnimationClip clip, List<TrackRef> trackRefs) throws IOException {
        ManifestDTO manifest = new ManifestDTO(
                OMAFormat.FORMAT_VERSION,
                clip.name(),
                clip.fps(),
                clip.duration(),
                clip.loop(),
                clip.modelRef(),
                trackRefs
        );
        byte[] json = objectMapper.writeValueAsBytes(manifest);
        ZipEntry entry = new ZipEntry(OMAFormat.MANIFEST_FILENAME);
        zos.putNextEntry(entry);
        zos.write(json);
        zos.closeEntry();
    }

    private void writeTrack(ZipOutputStream zos, String filename, Track track) throws IOException {
        List<KeyframeDTO> kfDtos = new ArrayList<>(track.size());
        for (Keyframe kf : track.keyframes()) {
            kfDtos.add(new KeyframeDTO(
                    kf.time(),
                    new float[]{kf.position().x, kf.position().y, kf.position().z},
                    new float[]{kf.rotation().x, kf.rotation().y, kf.rotation().z},
                    new float[]{kf.scale().x, kf.scale().y, kf.scale().z},
                    kf.easing().name()
            ));
        }
        TrackDTO trackDto = new TrackDTO(track.partId(), kfDtos);
        byte[] json = objectMapper.writeValueAsBytes(trackDto);
        ZipEntry entry = new ZipEntry(filename);
        zos.putNextEntry(entry);
        zos.write(json);
        zos.closeEntry();
    }

    // ===== DTOs (Jackson serializes public fields) =====

    public static class ManifestDTO {
        public String version;
        public String name;
        public float fps;
        public float duration;
        public boolean loop;
        public String modelRef;
        public List<TrackRef> tracks;

        public ManifestDTO() {}

        public ManifestDTO(String version, String name, float fps, float duration,
                           boolean loop, String modelRef, List<TrackRef> tracks) {
            this.version = version;
            this.name = name;
            this.fps = fps;
            this.duration = duration;
            this.loop = loop;
            this.modelRef = modelRef;
            this.tracks = tracks;
        }
    }

    public static class TrackRef {
        public String partId;
        public String partName;   // optional, used as a fallback rebind hint on load
        public String dataFile;

        public TrackRef() {}

        public TrackRef(String partId, String partName, String dataFile) {
            this.partId = partId;
            this.partName = partName;
            this.dataFile = dataFile;
        }
    }

    public static class TrackDTO {
        public String partId;
        public List<KeyframeDTO> keyframes;

        public TrackDTO() {}

        public TrackDTO(String partId, List<KeyframeDTO> keyframes) {
            this.partId = partId;
            this.keyframes = keyframes;
        }
    }

    public static class KeyframeDTO {
        public float time;
        public float[] position;
        public float[] rotation;
        public float[] scale;
        public String easing;

        public KeyframeDTO() {}

        public KeyframeDTO(float time, float[] position, float[] rotation, float[] scale, String easing) {
            this.time = time;
            this.position = position;
            this.rotation = rotation;
            this.scale = scale;
            this.easing = easing;
        }
    }

}
