package com.openmason.engine.format.omt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lightweight reader for Open Mason Texture (.OMT) archives.
 *
 * <p>Parses the manifest and pulls each layer's PNG bytes into memory without
 * decoding the pixel data. This keeps the engine module free of any image
 * decoding dependency (Skija, STB, AWT) — callers decode using whatever
 * library suits their context.
 *
 * <p>Backward-compatible with older OMT files: extra fields like face
 * mappings and material textures are tolerated and ignored.
 */
public final class OMTReader {

    private static final Logger logger = LoggerFactory.getLogger(OMTReader.class);
    private static final String MANIFEST_FILENAME = "manifest.json";
    private static final String LAYER_PREFIX = "layer_";
    private static final String LAYER_SUFFIX = ".png";

    private final ObjectMapper objectMapper;

    public OMTReader() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Read an OMT archive from raw ZIP bytes.
     */
    public OMTArchive read(byte[] omtBytes) throws IOException {
        if (omtBytes == null || omtBytes.length == 0) {
            throw new IOException("OMT data is empty");
        }
        try (InputStream in = new ByteArrayInputStream(omtBytes)) {
            return read(in);
        }
    }

    /**
     * Read an OMT archive from an arbitrary input stream.
     * Caller owns the stream — this method does not close it.
     */
    public OMTArchive read(InputStream in) throws IOException {
        byte[] manifestData = null;
        Map<String, byte[]> layerData = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] data = readEntry(zis);
                if (MANIFEST_FILENAME.equals(name)) {
                    manifestData = data;
                } else if (name.startsWith(LAYER_PREFIX) && name.endsWith(LAYER_SUFFIX)) {
                    layerData.put(name, data);
                }
                // material_*.png and unknown entries are ignored — UI textures
                // only need composited layer pixels.
                zis.closeEntry();
            }
        }

        if (manifestData == null) {
            throw new IOException("OMT archive is missing manifest.json");
        }

        ManifestDTO manifest = objectMapper.readValue(manifestData, ManifestDTO.class);
        if (manifest.canvasSize == null) {
            throw new IOException("OMT manifest is missing canvasSize");
        }
        if (manifest.layers == null || manifest.layers.isEmpty()) {
            throw new IOException("OMT manifest contains no layers");
        }

        OMTArchive.CanvasSize canvas = new OMTArchive.CanvasSize(
                manifest.canvasSize.width, manifest.canvasSize.height);

        List<OMTArchive.Layer> layers = new ArrayList<>(manifest.layers.size());
        for (LayerDTO dto : manifest.layers) {
            byte[] png = layerData.get(dto.dataFile);
            if (png == null) {
                throw new IOException("OMT layer PNG missing from archive: " + dto.dataFile);
            }
            layers.add(new OMTArchive.Layer(dto.name, dto.visible, dto.opacity, png));
        }

        int activeIndex = clampActive(manifest.activeLayerIndex, layers.size());
        logger.debug("Read OMT archive: {}x{}, {} layer(s)",
                canvas.width(), canvas.height(), layers.size());
        return new OMTArchive(canvas, layers, activeIndex);
    }

    private static int clampActive(int requested, int layerCount) {
        if (requested < 0) return 0;
        if (requested >= layerCount) return layerCount - 1;
        return requested;
    }

    private static byte[] readEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = zis.read(buf)) != -1) {
            baos.write(buf, 0, read);
        }
        return baos.toByteArray();
    }

    private static class ManifestDTO {
        public String version;
        public CanvasDTO canvasSize;
        public List<LayerDTO> layers;
        public int activeLayerIndex;
    }

    private static class CanvasDTO {
        public int width;
        public int height;
    }

    private static class LayerDTO {
        public String name;
        public boolean visible;
        public float opacity;
        public String dataFile;
    }
}
