package com.openmason.main.systems.scripting.doc;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;

/**
 * The model a script edits — the seam between the scripting command layer and
 * its execution target.
 *
 * <p>Two implementations: {@link HeadlessModelDocument} (no GL, CLI runner)
 * and {@code LiveModelDocument} (the viewport's live part manager + renderer
 * state; GL-thread only). The command layer performs all mutations through
 * {@link #parts()} and {@link #faceTextures()} so both targets behave
 * identically: topology edits are made per-part (import → op → flatten →
 * {@code replacePartGeometry}), which keeps part geometry authoritative and
 * mesh ranges correct in both modes.
 */
public interface ModelDocument {

    /** Part CRUD, transforms, hierarchy, geometry — the primary mutation surface. */
    ModelPartManager parts();

    /** Data-level face→material mappings and material definitions. */
    FaceTextureManager faceTextures();

    /**
     * The combined model mesh in OMO MeshData form (for export and
     * whole-model queries). May be null when the document is empty.
     */
    OMOFormat.MeshData extractMeshData();
}
