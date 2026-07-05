package com.openmason.engine.format.sbo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.omo.OMOReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parser for Stonebreak Object (.SBO) files.
 *
 * <p>Reads the SBO ZIP container, extracts the manifest and embedded OMO,
 * then delegates OMO parsing to {@link OMOReader}.
 *
 * <p>Validates the SHA-256 checksum of the embedded OMO to ensure integrity.
 */
public class SBOParser {

    private static final Logger logger = LoggerFactory.getLogger(SBOParser.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OMOReader omoReader = new OMOReader();

    /**
     * Parse an SBO file from disk.
     *
     * @param sboPath path to the .sbo file
     * @return complete parse result
     * @throws IOException if reading or parsing fails
     */
    public SBOParseResult parse(Path sboPath) throws IOException {
        if (!Files.exists(sboPath)) {
            throw new IOException("SBO file does not exist: " + sboPath);
        }

        SBOFormat.Document manifest = null;
        byte[] omoBytes = null;
        byte[] omtBytes = null;
        Map<String, byte[]> rawEntries = new HashMap<>();

        try (InputStream fis = Files.newInputStream(sboPath);
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                byte[] bytes = readBytes(zis);

                if (SBOFormat.MANIFEST_FILENAME.equals(entryName)) {
                    manifest = parseManifest(bytes);
                    logger.debug("Parsed SBO manifest: {} ({})", manifest.objectName(), manifest.objectId());
                } else if (SBOFormat.EMBEDDED_OMO_FILENAME.equals(entryName)) {
                    omoBytes = bytes;
                } else if (SBOFormat.EMBEDDED_OMT_FILENAME.equals(entryName)) {
                    omtBytes = bytes;
                } else if (entryName.startsWith(SBOFormat.STATES_DIR_PREFIX)) {
                    rawEntries.put(entryName, bytes);
                }

                zis.closeEntry();
            }
        }

        if (manifest == null) {
            throw new IOException("Missing manifest.json in SBO file: " + sboPath);
        }

        Map<String, byte[]> stateOmoBytes = new LinkedHashMap<>();
        Map<String, byte[]> stateOmtBytes = new LinkedHashMap<>();
        Map<String, OMOReader.ReadResult> stateOmoData = new LinkedHashMap<>();
        Map<String, byte[]> stateClipBytes = new LinkedHashMap<>();

        if (manifest.hasStates()) {
            for (SBOFormat.StateEntry e : manifest.states()) {
                byte[] data;
                if (e.name().equals(manifest.defaultStateName())) {
                    data = e.model() ? omoBytes : omtBytes;
                } else {
                    data = rawEntries.get(e.filename());
                }
                if (data == null) {
                    throw new IOException("State '" + e.name() + "' missing entry "
                            + e.filename() + " in SBO: " + sboPath);
                }
                validateNamedChecksum(e.name(), e.checksum(), data, sboPath);
                if (e.model()) {
                    stateOmoBytes.put(e.name(), data);
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
                        stateOmoData.put(e.name(), omoReader.read(bais));
                    }
                } else {
                    stateOmtBytes.put(e.name(), data);
                }

                if (e.hasAnimation()) {
                    byte[] clip = rawEntries.get(e.animation().filename());
                    if (clip == null) {
                        throw new IOException("State '" + e.name() + "' missing clip entry "
                                + e.animation().filename() + " in SBO: " + sboPath);
                    }
                    validateNamedChecksum(e.name() + " clip", e.animation().checksum(), clip, sboPath);
                    stateClipBytes.put(e.name(), clip);
                }
            }
        }

        if (manifest.isModelBearing()) {
            if (omoBytes == null) {
                throw new IOException("Manifest declares omoFile but model.omo missing: " + sboPath);
            }
            return parseModelBearing(manifest, omoBytes, sboPath, stateOmoBytes, stateOmtBytes,
                    stateOmoData, stateClipBytes);
        }

        if (manifest.isTextureOnly()) {
            if (omtBytes == null) {
                throw new IOException("Manifest declares textureFile but texture.omt missing: " + sboPath);
            }
            validateChecksum(manifest, omtBytes, sboPath);
            logger.info("Parsed texture-only SBO: {} ({}) - {} OMT bytes, states={}",
                    manifest.objectName(), manifest.objectId(), omtBytes.length, stateOmtBytes.size());
            return new SBOParseResult(manifest, null, null, null, null, null, omtBytes,
                    stateOmoBytes, stateOmtBytes, stateOmoData, stateClipBytes);
        }

        throw new IOException("SBO manifest declares neither omoFile nor textureFile: " + sboPath);
    }

    private SBOParseResult parseModelBearing(SBOFormat.Document manifest, byte[] omoBytes, Path sboPath,
                                              Map<String, byte[]> stateOmoBytes,
                                              Map<String, byte[]> stateOmtBytes,
                                              Map<String, OMOReader.ReadResult> stateOmoData,
                                              Map<String, byte[]> stateClipBytes) throws IOException {
        validateChecksum(manifest, omoBytes, sboPath);

        OMOReader.ReadResult omoResult;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(omoBytes)) {
            omoResult = omoReader.read(bais);
        }

        logger.info("Parsed SBO: {} ({}) - {} vertices, {} materials, states={}, clips={}",
                manifest.objectName(), manifest.objectId(),
                omoResult.meshData() != null ? omoResult.meshData().getVertexCount() : 0,
                omoResult.materials().size(),
                stateOmoBytes.size(),
                stateClipBytes.size());

        return new SBOParseResult(
                manifest,
                omoResult.document(),
                omoResult.meshData(),
                omoResult.faceMappings(),
                omoResult.materials(),
                omoResult.defaultTextureBytes(),
                null,
                stateOmoBytes,
                stateOmtBytes,
                stateOmoData,
                stateClipBytes
        );
    }

    private void validateNamedChecksum(String label, String expected, byte[] data, Path sboPath) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SBOFormat.CHECKSUM_ALGORITHM);
            String computed = HexFormat.of().formatHex(digest.digest(data));
            if (!computed.equalsIgnoreCase(expected)) {
                logger.warn("SBO state '{}' checksum mismatch in {}: expected={}, computed={}",
                        label, sboPath.getFileName(), expected, computed);
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            logger.warn("Cannot validate checksum for state '{}': {} not available",
                    label, SBOFormat.CHECKSUM_ALGORITHM);
        }
    }

    private SBOFormat.Document parseManifest(byte[] jsonBytes) throws IOException {
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        var root = objectMapper.readTree(json);

        SBOFormat.GameProperties gameProperties = null;
        if (root.has("gameProperties") && !root.get("gameProperties").isNull()) {
            var gp = root.get("gameProperties");
            gameProperties = new SBOFormat.GameProperties(
                    gp.has("numericId") ? gp.get("numericId").asInt() : -1,
                    gp.has("hardness") ? (float) gp.get("hardness").asDouble() : 1.0f,
                    !gp.has("solid") || gp.get("solid").asBoolean(),
                    !gp.has("breakable") || gp.get("breakable").asBoolean(),
                    gp.has("atlasX") ? gp.get("atlasX").asInt() : -1,
                    gp.has("atlasY") ? gp.get("atlasY").asInt() : -1,
                    gp.has("renderLayer") ? gp.get("renderLayer").asText() : null,
                    gp.has("transparent") && gp.get("transparent").asBoolean(),
                    gp.has("flower") && gp.get("flower").asBoolean(),
                    gp.has("stackable") && gp.get("stackable").asBoolean(),
                    gp.has("maxStackSize") ? gp.get("maxStackSize").asInt() : 64,
                    gp.has("category") ? gp.get("category").asText() : null,
                    !gp.has("placeable") || gp.get("placeable").asBoolean()
            );
        }

        String omoFile = root.has("omoFile") && !root.get("omoFile").isNull()
                ? nullIfBlank(root.get("omoFile").asText()) : null;
        String textureFile = root.has("textureFile") && !root.get("textureFile").isNull()
                ? nullIfBlank(root.get("textureFile").asText()) : null;

        List<SBOFormat.StateEntry> states = Collections.emptyList();
        String defaultState = null;
        if (root.has("states") && root.get("states").isArray() && !root.get("states").isEmpty()) {
            states = new ArrayList<>();
            for (var node : root.get("states")) {
                String name = node.get("name").asText();
                String file = node.get("file").asText();
                boolean model = node.has("model") && node.get("model").asBoolean();
                String stateChecksum = node.has("checksum") ? node.get("checksum").asText() : "";
                SBOFormat.AnimationRef animation = null;
                if (node.has("animation") && !node.get("animation").isNull()) {
                    var a = node.get("animation");
                    List<String> requiredParts = new ArrayList<>();
                    if (a.has("requiredParts") && a.get("requiredParts").isArray()) {
                        for (var p : a.get("requiredParts")) {
                            if (p != null && !p.isNull()) requiredParts.add(p.asText());
                        }
                    }
                    animation = new SBOFormat.AnimationRef(
                            a.has("file") ? a.get("file").asText() : SBOFormat.stateClipPath(name),
                            a.has("checksum") ? a.get("checksum").asText() : "",
                            a.has("clipName") && !a.get("clipName").isNull()
                                    ? a.get("clipName").asText() : null,
                            a.has("duration") ? (float) a.get("duration").asDouble() : 0f,
                            a.has("fps") ? (float) a.get("fps").asDouble() : 30f,
                            a.has("loop") && a.get("loop").asBoolean(),
                            requiredParts
                    );
                }
                states.add(new SBOFormat.StateEntry(name, file, model, stateChecksum, animation));
            }
            defaultState = root.has("defaultState") && !root.get("defaultState").isNull()
                    ? root.get("defaultState").asText() : null;
        }

        SBOFormat.RecipeData recipes = null;
        if (root.has("recipes") && !root.get("recipes").isNull()) {
            var recipesNode = root.get("recipes");
            List<SBOFormat.ShapedRecipe> shaped = new ArrayList<>();
            if (recipesNode.has("shaped") && recipesNode.get("shaped").isArray()) {
                for (var rNode : recipesNode.get("shaped")) {
                    int width = rNode.has("width") ? rNode.get("width").asInt() : 0;
                    int height = rNode.has("height") ? rNode.get("height").asInt() : 0;
                    int outputCount = rNode.has("outputCount") ? rNode.get("outputCount").asInt() : 1;
                    List<String> pattern = new ArrayList<>();
                    if (rNode.has("pattern") && rNode.get("pattern").isArray()) {
                        for (var slot : rNode.get("pattern")) {
                            pattern.add(slot.isNull() ? "" : slot.asText());
                        }
                    }
                    try {
                        shaped.add(new SBOFormat.ShapedRecipe(width, height, pattern, outputCount));
                    } catch (IllegalArgumentException ex) {
                        logger.warn("Skipping invalid shaped recipe in manifest: {}", ex.getMessage());
                    }
                }
            }
            if (!shaped.isEmpty()) {
                recipes = new SBOFormat.RecipeData(shaped);
            }
        }

        SBOFormat.SmeltingRecipeData smeltingRecipes = null;
        if (root.has("smeltingRecipes") && !root.get("smeltingRecipes").isNull()) {
            var smeltNode = root.get("smeltingRecipes");
            List<SBOFormat.SmeltingRecipeEntry> smeltList = new ArrayList<>();
            if (smeltNode.has("recipes") && smeltNode.get("recipes").isArray()) {
                for (var rNode : smeltNode.get("recipes")) {
                    String input = rNode.has("input") ? rNode.get("input").asText() : "";
                    int outputCount = rNode.has("outputCount") ? rNode.get("outputCount").asInt() : 1;
                    try {
                        smeltList.add(new SBOFormat.SmeltingRecipeEntry(input, outputCount));
                    } catch (IllegalArgumentException ex) {
                        logger.warn("Skipping invalid smelting recipe in manifest: {}", ex.getMessage());
                    }
                }
            }
            if (!smeltList.isEmpty()) {
                smeltingRecipes = new SBOFormat.SmeltingRecipeData(smeltList);
            }
        }

        SBOFormat.FuelData fuel = null;
        if (root.has("fuel") && !root.get("fuel").isNull()) {
            var fuelNode = root.get("fuel");
            int burnTicks = fuelNode.has("burnTicks") ? fuelNode.get("burnTicks").asInt() : 0;
            if (burnTicks > 0) {
                try {
                    fuel = new SBOFormat.FuelData(burnTicks);
                } catch (IllegalArgumentException ex) {
                    logger.warn("Skipping invalid fuel block in manifest: {}", ex.getMessage());
                }
            }
        }

        return new SBOFormat.Document(
                root.get("version").asText(),
                root.get("objectId").asText(),
                root.get("objectName").asText(),
                root.get("objectType").asText(),
                root.get("objectPack").asText(),
                root.get("checksum").asText(),
                root.get("author").asText(),
                root.has("description") ? root.get("description").asText() : null,
                root.has("createdAt") ? root.get("createdAt").asText() : null,
                omoFile,
                textureFile,
                gameProperties,
                states,
                defaultState,
                recipes,
                smeltingRecipes,
                fuel
        );
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private void validateChecksum(SBOFormat.Document manifest, byte[] omoBytes, Path sboPath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(SBOFormat.CHECKSUM_ALGORITHM);
            String computed = HexFormat.of().formatHex(digest.digest(omoBytes));
            if (!computed.equalsIgnoreCase(manifest.checksum())) {
                logger.warn("SBO checksum mismatch in {}: expected={}, computed={}",
                        sboPath.getFileName(), manifest.checksum(), computed);
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            logger.warn("Cannot validate SBO checksum: {} not available", SBOFormat.CHECKSUM_ALGORITHM);
        }
    }

    /**
     * Result of {@link #parseRaw(Path)} — manifest plus raw embedded asset bytes,
     * suitable for re-packing via {@link SBOSerializer#exportFromDocument}.
     *
     * @param manifest       parsed manifest (includes recipes, gameProperties, states)
     * @param defaultBytes   bytes of the legacy {@code model.omo} or {@code texture.omt} entry
     * @param stateBytes     per-state asset bytes keyed by state name; empty when no states
     * @param stateClipBytes per-state raw {@code .omanim} clip bytes keyed by state
     *                       name (1.6+); empty when no state carries a clip
     */
    public record RawParse(
            SBOFormat.Document manifest,
            byte[] defaultBytes,
            java.util.Map<String, byte[]> stateBytes,
            java.util.Map<String, byte[]> stateClipBytes
    ) {}

    /**
     * Lightweight parse that returns the manifest and raw asset bytes only.
     * Used by tools that need to round-trip an SBO without decoding the OMO/OMT
     * (e.g. the SBO editor's save flow, the recipe migrator).
     */
    public RawParse parseRaw(Path sboPath) throws IOException {
        if (!Files.exists(sboPath)) {
            throw new IOException("SBO file does not exist: " + sboPath);
        }

        SBOFormat.Document manifest = null;
        byte[] omoBytes = null;
        byte[] omtBytes = null;
        Map<String, byte[]> rawEntries = new HashMap<>();

        try (InputStream fis = Files.newInputStream(sboPath);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = readBytes(zis);
                if (SBOFormat.MANIFEST_FILENAME.equals(name)) {
                    manifest = parseManifest(bytes);
                } else if (SBOFormat.EMBEDDED_OMO_FILENAME.equals(name)) {
                    omoBytes = bytes;
                } else if (SBOFormat.EMBEDDED_OMT_FILENAME.equals(name)) {
                    omtBytes = bytes;
                } else if (name.startsWith(SBOFormat.STATES_DIR_PREFIX)) {
                    rawEntries.put(name, bytes);
                }
                zis.closeEntry();
            }
        }
        if (manifest == null) {
            throw new IOException("Missing manifest.json in SBO file: " + sboPath);
        }

        byte[] defaultBytes = manifest.isModelBearing() ? omoBytes : omtBytes;
        if (defaultBytes == null) {
            throw new IOException("Missing default asset entry in SBO: " + sboPath);
        }

        Map<String, byte[]> stateBytes = new LinkedHashMap<>();
        Map<String, byte[]> stateClipBytes = new LinkedHashMap<>();
        if (manifest.hasStates()) {
            for (SBOFormat.StateEntry e : manifest.states()) {
                if (e.name().equals(manifest.defaultStateName())) {
                    stateBytes.put(e.name(), defaultBytes);
                } else {
                    byte[] data = rawEntries.get(e.filename());
                    if (data == null) {
                        throw new IOException("State '" + e.name() + "' missing entry " + e.filename());
                    }
                    stateBytes.put(e.name(), data);
                }
                if (e.hasAnimation()) {
                    byte[] clip = rawEntries.get(e.animation().filename());
                    if (clip == null) {
                        throw new IOException("State '" + e.name() + "' missing clip entry "
                                + e.animation().filename());
                    }
                    stateClipBytes.put(e.name(), clip);
                }
            }
        }

        return new RawParse(manifest, defaultBytes, stateBytes, stateClipBytes);
    }

    private byte[] readBytes(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = zis.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }
        return baos.toByteArray();
    }
}
