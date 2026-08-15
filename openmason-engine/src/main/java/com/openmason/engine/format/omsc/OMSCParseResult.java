package com.openmason.engine.format.omsc;

import com.openmason.engine.format.omo.OMOReader;

import java.util.List;
import java.util.Map;

/**
 * Everything a parsed {@code .omsc} yields.
 *
 * @param manifest        the scene document
 * @param modelBytesById  raw embedded OMO bytes, keyed by model id
 * @param modelDataById   decoded embedded OMOs, keyed by model id; empty from
 *                        {@code parseRaw}, populated by {@code parse}
 */
public record OMSCParseResult(OMSCFormat.Document manifest,
                              Map<String, byte[]> modelBytesById,
                              Map<String, OMOReader.ReadResult> modelDataById) {

    public OMSCParseResult {
        modelBytesById = modelBytesById == null ? Map.of() : Map.copyOf(modelBytesById);
        modelDataById = modelDataById == null ? Map.of() : Map.copyOf(modelDataById);
    }

    public String sceneName() {
        return manifest.sceneName();
    }

    public List<OMSCFormat.InstanceEntry> instances() {
        return manifest.instances();
    }

    public List<OMSCFormat.ModelRef> models() {
        return manifest.models();
    }

    public byte[] bytesFor(String modelId) {
        return modelBytesById.get(modelId);
    }

    public OMOReader.ReadResult dataFor(String modelId) {
        return modelDataById.get(modelId);
    }

    public OMSCFormat.ModelRef refFor(String modelId) {
        return manifest.modelById(modelId);
    }

    /** Whether the archive actually carried bytes for this model. */
    public boolean hasEmbeddedCopy(String modelId) {
        byte[] bytes = modelBytesById.get(modelId);
        return bytes != null && bytes.length > 0;
    }
}
