package com.openmason.engine.format.omsc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openmason.engine.format.omo.OMOFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes {@code .omsc} archives.
 *
 * <p>Embedded models are written <b>verbatim</b> — the bytes read from the source
 * {@code .omo} go straight into the ZIP. Re-encoding through a parse/serialize round trip
 * would normalize fields, break checksum equality with the file on disk, and silently
 * drop anything either side does not model. This mirrors {@code SBOSerializer}.
 *
 * <p>Callers key their models by an arbitrary <em>session id</em>; this class maps those
 * onto content-hash ids, merging any that collapse to the same bytes and rewriting the
 * emitted instances accordingly. Doing that remap in exactly one place is what keeps a
 * mid-session model edit (which changes the content hash) from having to re-key live
 * objects.
 */
public class OMSCSerializer {

    private static final Logger logger = LoggerFactory.getLogger(OMSCSerializer.class);

    private final ObjectMapper objectMapper;

    public OMSCSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Save, reading each model's bytes from its source file.
     *
     * @param document    scene whose {@code ModelRef.modelId()}s are session ids
     * @param sourcePaths session id → source {@code .omo}
     */
    public boolean saveFromSources(OMSCFormat.Document document,
                                   Map<String, Path> sourcePaths,
                                   String outputPath) {
        Map<String, byte[]> bytes = new HashMap<>();
        if (sourcePaths != null) {
            for (Map.Entry<String, Path> entry : sourcePaths.entrySet()) {
                try {
                    bytes.put(entry.getKey(), Files.readAllBytes(entry.getValue()));
                } catch (IOException e) {
                    logger.error("Cannot read model source {}: {}", entry.getValue(), e.getMessage());
                    return false;
                }
            }
        }
        return save(document, bytes, outputPath);
    }

    /**
     * Save, with each model's bytes supplied directly — the path taken when a model only
     * exists as an embedded copy carried in from another project.
     *
     * @param modelBytes session id → OMO bytes
     */
    public boolean save(OMSCFormat.Document document,
                        Map<String, byte[]> modelBytes,
                        String outputPath) {
        if (document == null) {
            logger.error("Cannot save a null scene document");
            return false;
        }
        outputPath = OMSCFormat.ensureExtension(outputPath);

        Map<String, byte[]> supplied = modelBytes != null ? modelBytes : Map.of();

        // session id -> content id, and the deduplicated set of entries to write.
        Map<String, String> sessionToContent = new HashMap<>();
        Map<String, byte[]> bytesByContentId = new LinkedHashMap<>();
        Map<String, OMSCFormat.ModelRef> refsByContentId = new LinkedHashMap<>();

        for (OMSCFormat.ModelRef ref : document.models()) {
            byte[] data = supplied.get(ref.modelId());
            if (data == null) {
                logger.error("No bytes supplied for model '{}'", ref.modelId());
                return false;
            }
            String checksum = computeChecksum(data);
            String contentId = checksum.substring(0, OMSCFormat.MODEL_ID_LENGTH);
            sessionToContent.put(ref.modelId(), contentId);

            if (!bytesByContentId.containsKey(contentId)) {
                bytesByContentId.put(contentId, data);
                refsByContentId.put(contentId, new OMSCFormat.ModelRef(
                        contentId,
                        ref.path(),
                        ref.sourceName(),
                        OMSCFormat.modelEntryPath(contentId),
                        checksum,
                        data.length));
            }
        }

        List<OMSCFormat.InstanceEntry> remapped = new ArrayList<>(document.instances().size());
        for (OMSCFormat.InstanceEntry instance : document.instances()) {
            String contentId = sessionToContent.get(instance.modelId());
            if (contentId == null) {
                logger.error("Instance '{}' references unknown model '{}'", instance.id(), instance.modelId());
                return false;
            }
            remapped.add(new OMSCFormat.InstanceEntry(
                    instance.id(), instance.name(), contentId,
                    instance.transform(), instance.visible(), instance.locked()));
        }

        OMSCFormat.Document toWrite = new OMSCFormat.Document(
                OMSCFormat.FORMAT_VERSION,
                document.sceneName(), document.author(), document.description(),
                document.createdAt(), document.lastSavedAt(),
                new ArrayList<>(refsByContentId.values()), remapped,
                document.camera(), document.viewport());

        Path temp = null;
        try {
            temp = Files.createTempFile("omsc_save_", ".tmp");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(temp.toFile()))) {
                writeManifest(zos, toWrite);
                for (Map.Entry<String, byte[]> entry : bytesByContentId.entrySet()) {
                    writeEntry(zos, OMSCFormat.modelEntryPath(entry.getKey()), entry.getValue());
                }
            }
            // Plain REPLACE_EXISTING, not ATOMIC_MOVE: the temp file lives in the system
            // temp dir, which may be on a different filesystem.
            Files.move(temp, Path.of(outputPath), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Saved scene '{}' with {} instances / {} models to {}",
                    toWrite.sceneName(), remapped.size(), bytesByContentId.size(), outputPath);
            return true;

        } catch (IOException e) {
            logger.error("Error writing .OMSC file {}", outputPath, e);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
            return false;
        }
    }

    private void writeManifest(ZipOutputStream zos, OMSCFormat.Document document) throws IOException {
        byte[] json = objectMapper.writeValueAsBytes(new ManifestDTO(document));
        writeEntry(zos, OMSCFormat.MANIFEST_FILENAME, json);
    }

    private void writeEntry(ZipOutputStream zos, String entryName, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(data);
        zos.closeEntry();
    }

    private String computeChecksum(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(OMSCFormat.CHECKSUM_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(OMSCFormat.CHECKSUM_ALGORITHM + " unavailable", e);
        }
    }

    // ---- Jackson DTOs (public fields, auto-serialized; no annotations needed) ----

    @SuppressWarnings("unused")
    public static final class ManifestDTO {
        public String version;
        public String sceneName;
        public String author;
        public String description;
        public String createdAt;
        public String lastSavedAt;
        public List<ModelRefDTO> models = new ArrayList<>();
        public List<InstanceDTO> instances = new ArrayList<>();
        public CameraDTO camera;
        public ViewportDTO viewport;

        ManifestDTO(OMSCFormat.Document doc) {
            this.version = doc.version();
            this.sceneName = doc.sceneName();
            this.author = doc.author();
            this.description = doc.description();
            this.createdAt = doc.createdAt();
            this.lastSavedAt = doc.lastSavedAt();
            for (OMSCFormat.ModelRef ref : doc.models()) {
                models.add(new ModelRefDTO(ref));
            }
            for (OMSCFormat.InstanceEntry instance : doc.instances()) {
                instances.add(new InstanceDTO(instance));
            }
            this.camera = doc.camera() != null ? new CameraDTO(doc.camera()) : null;
            this.viewport = doc.viewport() != null ? new ViewportDTO(doc.viewport()) : null;
        }
    }

    @SuppressWarnings("unused")
    public static final class ModelRefDTO {
        public String modelId;
        public String path;
        public String sourceName;
        public String file;
        public String checksum;
        public long size;

        ModelRefDTO(OMSCFormat.ModelRef ref) {
            this.modelId = ref.modelId();
            this.path = ref.path();
            this.sourceName = ref.sourceName();
            this.file = ref.file();
            this.checksum = ref.checksum();
            this.size = ref.size();
        }
    }

    @SuppressWarnings("unused")
    public static final class InstanceDTO {
        public String id;
        public String name;
        public String modelId;
        public TransformDTO transform;
        public boolean visible;
        public boolean locked;

        InstanceDTO(OMSCFormat.InstanceEntry instance) {
            this.id = instance.id();
            this.name = instance.name();
            this.modelId = instance.modelId();
            // Identity transforms are omitted; the parser defaults them back.
            this.transform = instance.transform().isIdentity() ? null : new TransformDTO(instance.transform());
            this.visible = instance.visible();
            this.locked = instance.locked();
        }
    }

    @SuppressWarnings("unused")
    public static final class TransformDTO {
        public float posX, posY, posZ;
        public float rotX, rotY, rotZ;
        public float scaleX, scaleY, scaleZ;

        TransformDTO(OMOFormat.ModelTransform t) {
            this.posX = t.posX();
            this.posY = t.posY();
            this.posZ = t.posZ();
            this.rotX = t.rotX();
            this.rotY = t.rotY();
            this.rotZ = t.rotZ();
            this.scaleX = t.scaleX();
            this.scaleY = t.scaleY();
            this.scaleZ = t.scaleZ();
        }
    }

    @SuppressWarnings("unused")
    public static final class CameraDTO {
        public String mode;
        public float distance, pitch, yaw, fov;
        public float targetX, targetY, targetZ;

        CameraDTO(OMSCFormat.CameraState c) {
            this.mode = c.mode();
            this.distance = c.distance();
            this.pitch = c.pitch();
            this.yaw = c.yaw();
            this.fov = c.fov();
            this.targetX = c.targetX();
            this.targetY = c.targetY();
            this.targetZ = c.targetZ();
        }
    }

    @SuppressWarnings("unused")
    public static final class ViewportDTO {
        public int viewModeIndex, renderModeIndex;
        public boolean gridVisible, axesVisible, unrenderedMode, showVertices, showGizmo;
        public boolean gridSnappingEnabled;
        public float gridSnappingIncrement;

        ViewportDTO(OMSCFormat.ViewportState v) {
            this.viewModeIndex = v.viewModeIndex();
            this.renderModeIndex = v.renderModeIndex();
            this.gridVisible = v.gridVisible();
            this.axesVisible = v.axesVisible();
            this.unrenderedMode = v.unrenderedMode();
            this.showVertices = v.showVertices();
            this.showGizmo = v.showGizmo();
            this.gridSnappingEnabled = v.gridSnappingEnabled();
            this.gridSnappingIncrement = v.gridSnappingIncrement();
        }
    }

    /** Charset used for the manifest, exposed for tests that hand-build archives. */
    public static final java.nio.charset.Charset MANIFEST_CHARSET = StandardCharsets.UTF_8;
}
