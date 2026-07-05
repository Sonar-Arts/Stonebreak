package com.openmason.main.systems.scripting.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.ops.DeleteFaceOp;
import com.openmason.engine.rendering.model.gmr.editable.ops.ExtrudeFacesOp;
import com.openmason.engine.rendering.model.gmr.editable.ops.InsetFacesOp;
import com.openmason.engine.rendering.model.gmr.editable.ops.ScaleFacesOp;
import com.openmason.engine.rendering.model.gmr.editable.ops.SubdivideEdgeOp;
import com.openmason.engine.rendering.model.gmr.parts.MeshRange;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.scripting.doc.ModelDocument;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single command layer every scripting frontend (Python {@code om} API,
 * JSON op-batch, MCP script tools) drives. Stateless apart from the target
 * {@link ModelDocument} and an ops trace.
 *
 * <p>Conventions (shared by all frontends): Y-up, Euler XYZ rotations in
 * degrees, sizes are full extents in model units, parts addressed by name (or
 * id), face/vertex indices are part-local. Topology edits run per part
 * (import → op → flatten → {@code replacePartGeometry}) so part geometry stays
 * authoritative and survives later transforms.
 *
 * <p>Every mutation appends a normalized JSON op to {@link #opsTrace()},
 * making any script run — Python included — replayable as a JSON op batch.
 */
public final class ModelCommands {

    private final ModelDocument doc;
    private final ObjectMapper mapper;
    private final AnimCommands anim;
    private final List<ObjectNode> opsTrace = new ArrayList<>();

    public ModelCommands(ModelDocument doc, ObjectMapper mapper) {
        this(doc, mapper, null);
    }

    /**
     * @param animBaseDir directory relative animation save paths resolve
     *                    against (the CLI passes the output directory; live
     *                    runs pass null = absolute paths only)
     */
    public ModelCommands(ModelDocument doc, ObjectMapper mapper, java.nio.file.Path animBaseDir) {
        this.doc = doc;
        this.mapper = mapper;
        this.anim = new AnimCommands(doc, this::trace, animBaseDir);
    }

    /** Animation authoring commands (detached .omanim clips). */
    public AnimCommands anim() {
        return anim;
    }

    // ===================== Results =====================

    /** Compact part info returned by part-level commands. */
    public record PartInfo(String id, String name, int verts, int faces, int tris) {
        static PartInfo from(ModelPartManager pm, ModelPartDescriptor d) {
            PartMeshRebuilder.PartGeometry geo = pm.getPartGeometry(d.id());
            return new PartInfo(d.id(), d.name(),
                    geo != null ? geo.vertexCount() : 0,
                    geo != null ? geo.faceCount() : 0,
                    geo != null ? geo.indexCount() / 3 : 0);
        }
    }

    /** Result of a face topology op: the newly created part-local face ids. */
    public record FacesResult(int[] newLocalFaceIds) {
    }

    // ===================== Parts =====================

    public PartInfo createPart(String shape, String name, Vector3f size,
                               Vector3f position, Vector3f rotation, String parentIdOrName) {
        PartShapeFactory.Shape s = parseShape(shape);
        requireNewName(name);
        Vector3f sz = size != null ? size : new Vector3f(1, 1, 1);
        if (sz.x <= 0 || sz.y <= 0 || sz.z <= 0) {
            throw new CommandException("size must be positive on every axis, got "
                    + fmt(sz), "sizes are full extents in model units");
        }

        PartMeshRebuilder.PartGeometry geo = PartShapeFactory.createGeometry(s, name, sz);
        ModelPartManager pm = doc.parts();
        ModelPartDescriptor part = pm.addPartFromGeometry(name, geo, new Vector3f());

        if (position != null || rotation != null) {
            pm.setPartTransform(part.id(), new PartTransform(
                    new Vector3f(),
                    position != null ? new Vector3f(position) : new Vector3f(),
                    rotation != null ? new Vector3f(rotation) : new Vector3f(),
                    new Vector3f(1, 1, 1)));
        }
        if (parentIdOrName != null) {
            setParent(name, parentIdOrName, false);
        }

        trace("create_part", op -> {
            op.put("shape", s.name().toLowerCase(Locale.ROOT));
            op.put("name", name);
            op.set("size", vecNode(sz));
            if (position != null) op.set("position", vecNode(position));
            if (rotation != null) op.set("rotation", vecNode(rotation));
            if (parentIdOrName != null) op.put("parent", parentIdOrName);
        });
        return info(name);
    }

    public PartInfo duplicatePart(String srcIdOrName, String newName, Vector3f offset) {
        ModelPartDescriptor src = resolve(srcIdOrName);
        requireNewName(newName);
        ModelPartManager pm = doc.parts();
        ModelPartDescriptor dup = pm.duplicatePart(src.id())
                .orElseThrow(() -> new CommandException("Duplicate failed for part '" + src.name() + "'"));
        pm.renamePart(dup.id(), newName);
        PartTransform t = src.transform();
        Vector3f pos = new Vector3f(t.position());
        if (offset != null) pos.add(offset);
        pm.setPartTransform(dup.id(), new PartTransform(
                new Vector3f(t.origin()), pos, new Vector3f(t.rotation()), new Vector3f(t.scale())));

        trace("duplicate_part", op -> {
            op.put("part", src.name());
            op.put("name", newName);
            if (offset != null) op.set("offset", vecNode(offset));
        });
        return info(newName);
    }

    /**
     * Mirror a part's geometry across a model axis into a new part. Geometry
     * coordinates are negated on the axis and triangle winding reversed (so
     * faces stay outward); the transform mirrors position/origin on the axis
     * and negates the other two Euler angles.
     */
    public PartInfo mirrorPart(String srcIdOrName, char axis, String newName) {
        ModelPartDescriptor src = resolve(srcIdOrName);
        int ax = switch (Character.toLowerCase(axis)) {
            case 'x' -> 0;
            case 'y' -> 1;
            case 'z' -> 2;
            default -> throw new CommandException("Unknown mirror axis '" + axis + "'",
                    "use \"x\", \"y\" or \"z\"");
        };
        String name = newName != null ? newName : src.name() + "_mirror";
        requireNewName(name);

        ModelPartManager pm = doc.parts();
        PartMeshRebuilder.PartGeometry geo = pm.getPartGeometry(src.id());
        if (geo == null) throw new CommandException("Part '" + src.name() + "' has no geometry");

        float[] verts = geo.vertices().clone();
        for (int i = ax; i < verts.length; i += 3) {
            verts[i] = -verts[i];
        }
        int[] indices = geo.indices().clone();
        for (int t = 0; t + 2 < indices.length; t += 3) {
            int tmp = indices[t + 1];
            indices[t + 1] = indices[t + 2];
            indices[t + 2] = tmp;
        }
        PartMeshRebuilder.PartGeometry mirrored = PartMeshRebuilder.PartGeometry.of(
                verts,
                geo.texCoords() != null ? geo.texCoords().clone() : null,
                indices,
                geo.triangleToFaceId() != null ? geo.triangleToFaceId().clone() : null);

        ModelPartDescriptor part = pm.addPartFromGeometry(name, mirrored, new Vector3f());
        PartTransform t = src.transform();
        Vector3f origin = mirrorAxis(t.origin(), ax);
        Vector3f position = mirrorAxis(t.position(), ax);
        Vector3f rotation = mirrorRotation(t.rotation(), ax);
        pm.setPartTransform(part.id(), new PartTransform(
                origin, position, rotation, new Vector3f(t.scale())));
        if (src.parentId() != null) {
            pm.setPartParent(part.id(), src.parentId());
        }

        trace("mirror_part", op -> {
            op.put("part", src.name());
            op.put("axis", String.valueOf(Character.toLowerCase(axis)));
            op.put("name", name);
        });
        return info(name);
    }

    public void removePart(String idOrName) {
        ModelPartDescriptor part = resolve(idOrName);
        doc.parts().removePart(part.id());
        trace("remove_part", op -> op.put("part", part.name()));
    }

    public PartInfo renamePart(String idOrName, String newName) {
        ModelPartDescriptor part = resolve(idOrName);
        requireNewName(newName);
        doc.parts().renamePart(part.id(), newName);
        String oldName = part.name();
        trace("rename_part", op -> {
            op.put("part", oldName);
            op.put("name", newName);
        });
        return info(newName);
    }

    public void setParent(String idOrName, String parentIdOrNameOrNull, boolean traced) {
        ModelPartDescriptor part = resolve(idOrName);
        String parentId = null;
        String parentName = null;
        if (parentIdOrNameOrNull != null) {
            ModelPartDescriptor parent = resolve(parentIdOrNameOrNull);
            parentId = parent.id();
            parentName = parent.name();
        }
        if (!doc.parts().setPartParent(part.id(), parentId)) {
            throw new CommandException("Cannot parent '" + part.name() + "' to '"
                    + parentIdOrNameOrNull + "'", "parenting to a descendant would create a cycle");
        }
        if (traced) {
            String pn = parentName;
            trace("set_parent", op -> {
                op.put("part", part.name());
                if (pn != null) op.put("parent", pn); // absent = unparent
            });
        }
    }

    public void setVisibility(String idOrName, boolean visible) {
        ModelPartDescriptor part = resolve(idOrName);
        doc.parts().setPartVisible(part.id(), visible);
        trace("set_visibility", op -> {
            op.put("part", part.name());
            op.put("visible", visible);
        });
    }

    // ===================== Transforms =====================

    /** Set any subset of origin/position/rotation/scale absolutely (null = keep). */
    public void setTransform(String idOrName, Vector3f origin, Vector3f position,
                             Vector3f rotation, Vector3f scale) {
        ModelPartDescriptor part = resolve(idOrName);
        PartTransform t = part.transform();
        PartTransform next = new PartTransform(
                origin != null ? new Vector3f(origin) : new Vector3f(t.origin()),
                position != null ? new Vector3f(position) : new Vector3f(t.position()),
                rotation != null ? new Vector3f(rotation) : new Vector3f(t.rotation()),
                scale != null ? new Vector3f(scale) : new Vector3f(t.scale()));
        doc.parts().setPartTransform(part.id(), next);
        trace("set_transform", op -> {
            op.put("part", part.name());
            if (origin != null) op.set("origin", vecNode(origin));
            if (position != null) op.set("position", vecNode(position));
            if (rotation != null) op.set("rotation", vecNode(rotation));
            if (scale != null) op.set("scale", vecNode(scale));
        });
    }

    public void translate(String idOrName, Vector3f delta) {
        ModelPartDescriptor part = resolve(idOrName);
        doc.parts().translatePart(part.id(), delta);
        trace("translate", op -> {
            op.put("part", part.name());
            op.set("delta", vecNode(delta));
        });
    }

    public void rotate(String idOrName, Vector3f eulerDeltaDeg) {
        ModelPartDescriptor part = resolve(idOrName);
        doc.parts().rotatePart(part.id(), eulerDeltaDeg);
        trace("rotate", op -> {
            op.put("part", part.name());
            op.set("delta", vecNode(eulerDeltaDeg));
        });
    }

    public void scale(String idOrName, Vector3f factors) {
        ModelPartDescriptor part = resolve(idOrName);
        doc.parts().scalePart(part.id(), factors);
        trace("scale", op -> {
            op.put("part", part.name());
            op.set("factors", vecNode(factors));
        });
    }

    // ===================== Face selection =====================

    /** Part-local face ids facing a world-space direction ("+y", "up", "north", ...). */
    public int[] selectFacesByDirection(String idOrName, String direction) {
        ModelPartDescriptor part = resolve(idOrName);
        return FaceSelector.facesByDirection(doc.parts(), part, direction);
    }

    // ===================== Topology ops (per-part) =====================

    /**
     * Extrude each face individually along its own normal (in part-local
     * space). Original ids keep the moved cap; returns the new side-quad ids.
     */
    public FacesResult extrudeFaces(String idOrName, int[] localFaceIds, float offset) {
        return topologyOp(idOrName, localFaceIds, "extrude_faces",
                op -> op.put("offset", offset),
                (mesh, ids) -> {
                    List<ExtrudeFacesOp.FaceResult> results = ExtrudeFacesOp.apply(mesh, ids, offset);
                    if (results.isEmpty()) {
                        throw new CommandException("Extrude produced no faces (offset=" + offset + ")");
                    }
                    List<Integer> created = new ArrayList<>();
                    for (ExtrudeFacesOp.FaceResult r : results) {
                        inheritMaterialLater(created, r.faceId(), r.sideFaceIds());
                    }
                    return created;
                });
    }

    /**
     * Inset each face individually (even-thickness border quads; original ids
     * keep the inner cap). Returns the new border-quad ids.
     */
    public FacesResult insetFaces(String idOrName, int[] localFaceIds, float amount) {
        if (amount <= 0) {
            throw new CommandException("inset amount must be > 0, got " + amount);
        }
        return topologyOp(idOrName, localFaceIds, "inset_faces",
                op -> op.put("amount", amount),
                (mesh, ids) -> {
                    List<InsetFacesOp.FaceResult> results = InsetFacesOp.apply(mesh, ids, amount);
                    if (results.isEmpty()) {
                        throw new CommandException("Inset produced no faces (amount=" + amount + ")");
                    }
                    List<Integer> created = new ArrayList<>();
                    for (InsetFacesOp.FaceResult r : results) {
                        inheritMaterialLater(created, r.faceId(), r.borderFaceIds());
                    }
                    return created;
                });
    }

    /** Uniformly scale faces about their area-weighted centroid (pivot null). */
    public void scaleFaces(String idOrName, int[] localFaceIds, float factor, Vector3f pivotOrNull) {
        topologyOp(idOrName, localFaceIds, "scale_faces",
                op -> {
                    op.put("factor", factor);
                    if (pivotOrNull != null) op.set("pivot", vecNode(pivotOrNull));
                },
                (mesh, ids) -> {
                    ScaleFacesOp.Result r = ScaleFacesOp.apply(mesh, ids, factor, pivotOrNull);
                    if (r.movedVertexIds().length == 0) {
                        throw new CommandException("Scale faces moved no vertices (factor=" + factor + ")");
                    }
                    return List.of();
                });
    }

    /** Delete faces by part-local id. */
    public void deleteFaces(String idOrName, int[] localFaceIds) {
        ModelPartDescriptor part = resolve(idOrName);
        validateLocalFaces(part, localFaceIds);
        ModelPartManager pm = doc.parts();
        PartMeshEditor.ImportedPart imported = PartMeshEditor.importPart(pm.getPartGeometry(part.id()));

        MeshRange oldRange = requireRange(part);
        for (int local : localFaceIds) {
            if (!DeleteFaceOp.apply(imported.mesh(), local)) {
                throw new CommandException("Face " + local + " not found in part '" + part.name() + "'");
            }
            doc.faceTextures().removeFaceMapping(oldRange.faceStart() + local);
        }

        applyFlattened(part, imported.mesh());
        trace("delete_faces", op -> {
            op.put("part", part.name());
            op.set("faces", intArrayNode(localFaceIds));
        });
    }

    /**
     * Insert a vertex on the edge between two part-local vertices at
     * parametric t (0..1 exclusive). Returns the new vertex's part-local
     * index (position-matched after the rebuild; -1 if unmappable).
     */
    public int subdivideEdge(String idOrName, int localVA, int localVB, float t) {
        if (!(t > 0.0f && t < 1.0f)) {
            throw new CommandException("t must be strictly between 0 and 1, got " + t,
                    "0.5 inserts the vertex at the edge midpoint");
        }
        ModelPartDescriptor part = resolve(idOrName);
        ModelPartManager pm = doc.parts();
        PartMeshRebuilder.PartGeometry geo = pm.getPartGeometry(part.id());
        PartMeshEditor.ImportedPart imported = PartMeshEditor.importPart(geo);

        int vA = soupVertexId(imported, localVA, part);
        int vB = soupVertexId(imported, localVB, part);
        SubdivideEdgeOp.Result result = SubdivideEdgeOp.apply(imported.mesh(), vA, vB, t);
        if (result == null || result.newVertexId() < 0) {
            throw new CommandException("No edge between vertices " + localVA + " and " + localVB
                    + " in part '" + part.name() + "'",
                    "vertex pairs must be endpoints of an existing edge");
        }
        Vector3f newPos = new Vector3f(imported.mesh().position(result.newVertexId()));

        applyFlattened(part, imported.mesh());
        trace("subdivide_edge", op -> {
            op.put("part", part.name());
            op.put("v_a", localVA);
            op.put("v_b", localVB);
            op.put("t", t);
        });
        return findLocalVertexAt(part, newPos);
    }

    /** Replace a part's geometry wholesale (part-local positions). */
    public PartInfo setGeometry(String idOrName, float[] vertices, int[] indices,
                                float[] texCoords, int[] triangleToFaceId) {
        ModelPartDescriptor part = resolve(idOrName);
        if (vertices == null || vertices.length < 9 || vertices.length % 3 != 0) {
            throw new CommandException("vertices must be a flat [x,y,z,...] array with at least 3 vertices");
        }
        if (indices == null || indices.length < 3 || indices.length % 3 != 0) {
            throw new CommandException("indices must be a flat triangle array (length divisible by 3)");
        }
        int vertexCount = vertices.length / 3;
        for (int i : indices) {
            if (i < 0 || i >= vertexCount) {
                throw new CommandException("index " + i + " out of range (part has "
                        + vertexCount + " vertices)");
            }
        }
        if (texCoords != null && texCoords.length != vertexCount * 2) {
            throw new CommandException("tex_coords length must be 2 × vertex count ("
                    + vertexCount * 2 + "), got " + texCoords.length);
        }
        int triangleCount = indices.length / 3;
        int[] triFace = triangleToFaceId;
        if (triFace != null && triFace.length != triangleCount) {
            throw new CommandException("triangle_to_face_id length must equal triangle count ("
                    + triangleCount + "), got " + triFace.length);
        }
        if (triFace == null) {
            triFace = new int[triangleCount];
            for (int i = 0; i < triangleCount; i++) triFace[i] = i;
        }
        float[] uvs = texCoords != null ? texCoords : new float[vertexCount * 2];

        PartMeshRebuilder.PartGeometry geo = PartMeshRebuilder.PartGeometry.of(
                vertices, uvs, indices, triFace);
        if (!doc.parts().replacePartGeometry(part.id(), geo)) {
            throw new CommandException("Geometry replace failed for part '" + part.name() + "'");
        }
        final int[] traceTriFace = triFace;
        trace("set_geometry", op -> {
            op.put("part", part.name());
            op.set("vertices", floatArrayNode(vertices));
            op.set("indices", intArrayNode(indices));
            if (texCoords != null) op.set("tex_coords", floatArrayNode(texCoords));
            op.set("triangle_to_face_id", intArrayNode(traceTriFace));
        });
        return info(part.name());
    }

    // ===================== Vertices =====================

    /** Move part-local vertices by delta, or to an absolute position. */
    public void moveVertices(String idOrName, int[] localIndices, Vector3f xyz, boolean absolute) {
        ModelPartDescriptor part = resolve(idOrName);
        ModelPartManager pm = doc.parts();
        PartMeshRebuilder.PartGeometry geo = pm.getPartGeometry(part.id());
        if (geo == null || geo.vertices() == null) {
            throw new CommandException("Part '" + part.name() + "' has no geometry");
        }
        int count = geo.vertexCount();
        for (int local : localIndices) {
            if (local < 0 || local >= count) {
                throw new CommandException("vertex index " + local + " out of range; part '"
                        + part.name() + "' has vertices 0.." + (count - 1));
            }
        }
        float[] verts = geo.vertices();
        for (int local : localIndices) {
            Vector3f next;
            if (absolute) {
                next = new Vector3f(xyz);
            } else {
                int o = local * 3;
                next = new Vector3f(verts[o], verts[o + 1], verts[o + 2]).add(xyz);
            }
            pm.updatePartVertex(part.id(), local, next.x, next.y, next.z);
        }
        trace("move_vertices", op -> {
            op.put("part", part.name());
            op.set("vertices", intArrayNode(localIndices));
            op.set("xyz", vecNode(xyz));
            op.put("absolute", absolute);
        });
    }

    /** Position of a part-local vertex. */
    public float[] vertex(String idOrName, int localIndex) {
        ModelPartDescriptor part = resolve(idOrName);
        PartMeshRebuilder.PartGeometry geo = doc.parts().getPartGeometry(part.id());
        if (geo == null || geo.vertices() == null) {
            throw new CommandException("Part '" + part.name() + "' has no geometry");
        }
        int count = geo.vertexCount();
        if (localIndex < 0 || localIndex >= count) {
            throw new CommandException("vertex index " + localIndex + " out of range; part '"
                    + part.name() + "' has vertices 0.." + (count - 1));
        }
        int o = localIndex * 3;
        float[] v = geo.vertices();
        return new float[]{v[o], v[o + 1], v[o + 2]};
    }

    // ===================== Materials / UVs (data-level) =====================

    /**
     * Define (or redefine) a named material. Data-level only: texture pixels
     * are authored later with the face-texture tools; tint/layer/emissive
     * serialize into the OMO.
     */
    public int defineMaterial(String name, int[] tintRGBA, boolean emissive, String renderLayer) {
        if (name == null || name.isBlank()) {
            throw new CommandException("material name is required");
        }
        MaterialDefinition.RenderLayer layer = parseRenderLayer(renderLayer);
        int tint = packTint(tintRGBA);

        FaceTextureManager ftm = doc.faceTextures();
        Integer existingId = materialIdByName(name);
        int id = existingId != null ? existingId : nextMaterialId();
        int textureId = existingId != null ? ftm.getMaterial(id).textureId() : 0;
        ftm.registerMaterial(new MaterialDefinition(id, name, textureId, layer,
                new MaterialDefinition.MaterialProperties(emissive, tint)));

        trace("define_material", op -> {
            op.put("name", name);
            if (tintRGBA != null) op.set("tint", intArrayNode(tintRGBA));
            if (emissive) op.put("emissive", true);
            if (layer != MaterialDefinition.RenderLayer.OPAQUE) op.put("layer", layer.name());
        });
        return id;
    }

    /** Assign a material (by name) to part-local faces. */
    public void setFaceMaterial(String idOrName, int[] localFaceIds, String materialName) {
        ModelPartDescriptor part = resolve(idOrName);
        validateLocalFaces(part, localFaceIds);
        Integer materialId = materialIdByName(materialName);
        if (materialId == null) {
            List<String> known = doc.faceTextures().getAllMaterials().stream()
                    .map(MaterialDefinition::name).toList();
            throw new CommandException("No material '" + materialName + "'. Known: "
                    + String.join(", ", known), "define it first with define_material");
        }
        MeshRange range = requireRange(part);
        for (int local : localFaceIds) {
            doc.faceTextures().assignDefaultMapping(range.faceStart() + local, materialId);
        }
        trace("set_face_material", op -> {
            op.put("part", part.name());
            op.set("faces", intArrayNode(localFaceIds));
            op.put("material", materialName);
        });
    }

    /** Set the UV region (+ optional rotation) for part-local faces. */
    public void setFaceUV(String idOrName, int[] localFaceIds, float[] region, int rotationDeg) {
        ModelPartDescriptor part = resolve(idOrName);
        validateLocalFaces(part, localFaceIds);
        if (region == null || region.length != 4) {
            throw new CommandException("region must be [u0,v0,u1,v1] with values in 0..1");
        }
        FaceTextureMapping.UVRegion uvRegion =
                new FaceTextureMapping.UVRegion(region[0], region[1], region[2], region[3]);
        FaceTextureMapping.UVRotation rotation = FaceTextureMapping.UVRotation.fromDegrees(rotationDeg);
        MeshRange range = requireRange(part);
        FaceTextureManager ftm = doc.faceTextures();
        for (int local : localFaceIds) {
            int global = range.faceStart() + local;
            FaceTextureMapping existing = ftm.getFaceMapping(global);
            int materialId = existing != null ? existing.materialId()
                    : MaterialDefinition.DEFAULT.materialId();
            ftm.setFaceMapping(new FaceTextureMapping(global, materialId, uvRegion, rotation, false));
        }
        trace("set_face_uv", op -> {
            op.put("part", part.name());
            op.set("faces", intArrayNode(localFaceIds));
            op.set("region", floatArrayNode(region));
            if (rotationDeg != 0) op.put("rotation", rotationDeg);
        });
    }

    // ===================== Queries =====================

    public PartInfo info(String idOrName) {
        ModelPartDescriptor part = resolve(idOrName);
        return PartInfo.from(doc.parts(), part);
    }

    public List<String> partNames() {
        return doc.parts().getAllParts().stream().map(ModelPartDescriptor::name).toList();
    }

    public ModelSummary summary() {
        // Exclude the always-present default material from the count.
        int materials = Math.max(0, doc.faceTextures().getMaterialCount() - 1);
        return ModelSummary.from(doc.parts(), materials);
    }

    public List<ObjectNode> opsTrace() {
        return opsTrace;
    }

    // ===================== Internals =====================

    @FunctionalInterface
    private interface TopologyOp {
        /** Run against the imported per-part mesh; return created face ids (mesh ids). */
        List<Integer> apply(EditableMesh mesh, int[] localFaceIds);
    }

    /** Deferred material inheritance: (created ids ×2 flat: [createdId, sourceId, ...]). */
    private void inheritMaterialLater(List<Integer> created, int sourceFaceId, int[] createdIds) {
        for (int id : createdIds) {
            created.add(id);
            created.add(sourceFaceId);
        }
    }

    /**
     * Shared per-part topology plumbing: validate, import, op, flatten,
     * remap this part's face-material keys for intra-part id shifts, replace
     * geometry, then localize created ids and inherit source materials.
     */
    private FacesResult topologyOp(String idOrName, int[] localFaceIds, String opName,
                                   java.util.function.Consumer<ObjectNode> traceArgs,
                                   TopologyOp op) {
        ModelPartDescriptor part = resolve(idOrName);
        validateLocalFaces(part, localFaceIds);
        ModelPartManager pm = doc.parts();
        PartMeshEditor.ImportedPart imported = PartMeshEditor.importPart(pm.getPartGeometry(part.id()));

        List<Integer> createdWithSource = op.apply(imported.mesh(), localFaceIds);

        Map<Integer, Integer> faceIdToLocal = applyFlattened(part, imported.mesh());

        // Localize created ids and inherit each source face's material.
        MeshRange newRange = requireRange(doc.parts().getPartById(part.id()).orElse(part));
        List<Integer> newLocals = new ArrayList<>();
        for (int i = 0; i + 1 < createdWithSource.size(); i += 2) {
            Integer newLocal = faceIdToLocal.get(createdWithSource.get(i));
            Integer sourceLocal = faceIdToLocal.get(createdWithSource.get(i + 1));
            if (newLocal == null) continue;
            newLocals.add(newLocal);
            int materialId = MaterialDefinition.DEFAULT.materialId();
            if (sourceLocal != null) {
                FaceTextureMapping sourceMapping =
                        doc.faceTextures().getFaceMapping(newRange.faceStart() + sourceLocal);
                if (sourceMapping != null) materialId = sourceMapping.materialId();
            }
            doc.faceTextures().assignDefaultMapping(newRange.faceStart() + newLocal, materialId);
        }

        trace(opName, node -> {
            node.put("part", part.name());
            node.set("faces", intArrayNode(localFaceIds));
            traceArgs.accept(node);
        });
        int[] out = new int[newLocals.size()];
        for (int i = 0; i < out.length; i++) out[i] = newLocals.get(i);
        return new FacesResult(out);
    }

    /**
     * Flatten the edited mesh back into the part (compact face ids), remapping
     * this part's face-material keys where local ids shifted.
     *
     * @return pre-flatten face id → new local id
     */
    private Map<Integer, Integer> applyFlattened(ModelPartDescriptor part, EditableMesh mesh) {
        PartMeshEditor.FlattenResult flat = PartMeshEditor.flatten(mesh);

        MeshRange oldRange = requireRange(part);
        Map<Integer, Integer> globalRemap = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : flat.faceIdToLocal().entrySet()) {
            if (!e.getKey().equals(e.getValue())) {
                globalRemap.put(oldRange.faceStart() + e.getKey(),
                        oldRange.faceStart() + e.getValue());
            }
        }
        if (!globalRemap.isEmpty()) {
            doc.faceTextures().remapFaceIds(globalRemap);
        }

        if (!doc.parts().replacePartGeometry(part.id(), flat.geometry())) {
            throw new CommandException("Geometry update failed for part '" + part.name() + "'");
        }
        return flat.faceIdToLocal();
    }

    private int soupVertexId(PartMeshEditor.ImportedPart imported, int localVertex,
                             ModelPartDescriptor part) {
        int[] map = imported.soupToVertexId();
        if (localVertex < 0 || localVertex >= map.length || map[localVertex] < 0) {
            throw new CommandException("vertex index " + localVertex + " out of range; part '"
                    + part.name() + "' has vertices 0.." + (map.length - 1));
        }
        return map[localVertex];
    }

    private int findLocalVertexAt(ModelPartDescriptor part, Vector3f pos) {
        PartMeshRebuilder.PartGeometry geo = doc.parts().getPartGeometry(part.id());
        if (geo == null || geo.vertices() == null) return -1;
        float[] v = geo.vertices();
        float epsSq = 1e-8f;
        for (int i = 0; i * 3 + 2 < v.length; i++) {
            float dx = v[i * 3] - pos.x, dy = v[i * 3 + 1] - pos.y, dz = v[i * 3 + 2] - pos.z;
            if (dx * dx + dy * dy + dz * dz < epsSq) return i;
        }
        return -1;
    }

    private ModelPartDescriptor resolve(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            throw new CommandException("part name is required");
        }
        ModelPartManager pm = doc.parts();
        return pm.getPartById(idOrName)
                .or(() -> pm.getPartByName(idOrName))
                .orElseThrow(() -> {
                    List<String> known = partNames();
                    String list = known.isEmpty() ? "none exist yet"
                            : "known parts: " + String.join(", ",
                                    known.subList(0, Math.min(known.size(), 15)));
                    return new CommandException("No part '" + idOrName + "' (" + list + ")",
                            "part names are case-sensitive");
                });
    }

    private void requireNewName(String name) {
        if (name == null || name.isBlank()) {
            throw new CommandException("part name is required");
        }
        if (doc.parts().getPartByName(name).isPresent()) {
            throw new CommandException("A part named '" + name + "' already exists",
                    "pick a unique name; scripts address parts by name");
        }
    }

    private void validateLocalFaces(ModelPartDescriptor part, int[] localFaceIds) {
        if (localFaceIds == null || localFaceIds.length == 0) {
            throw new CommandException("faces must be a non-empty selection",
                    "use face indices or a facing direction like \"+y\"");
        }
        PartMeshRebuilder.PartGeometry geo = doc.parts().getPartGeometry(part.id());
        int count = geo != null ? geo.faceCount() : 0;
        for (int local : localFaceIds) {
            if (local < 0 || local >= count) {
                throw new CommandException("face id " + local + " out of range; part '"
                        + part.name() + "' has faces 0.." + (count - 1));
            }
        }
    }

    private static MeshRange requireRange(ModelPartDescriptor part) {
        MeshRange range = part.meshRange();
        if (range == null) {
            throw new CommandException("Part '" + part.name() + "' has no mesh range");
        }
        return range;
    }

    private static PartShapeFactory.Shape parseShape(String name) {
        if (name == null || name.isBlank()) {
            throw new CommandException("shape is required", validShapesHint());
        }
        try {
            return PartShapeFactory.Shape.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CommandException("Unknown shape '" + name + "'", validShapesHint());
        }
    }

    private static String validShapesHint() {
        StringBuilder sb = new StringBuilder("valid shapes: ");
        PartShapeFactory.Shape[] shapes = PartShapeFactory.Shape.values();
        for (int i = 0; i < shapes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(shapes[i].name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static MaterialDefinition.RenderLayer parseRenderLayer(String name) {
        if (name == null || name.isBlank()) return MaterialDefinition.RenderLayer.OPAQUE;
        try {
            return MaterialDefinition.RenderLayer.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CommandException("Unknown render layer '" + name + "'",
                    "valid layers: opaque, cutout, translucent");
        }
    }

    /** Pack [r,g,b,a] (0..255 each) into ARGB int; null = white. */
    private static int packTint(int[] rgba) {
        if (rgba == null) return 0xFFFFFFFF;
        if (rgba.length != 4) {
            throw new CommandException("tint must be [r,g,b,a] with values 0..255");
        }
        for (int c : rgba) {
            if (c < 0 || c > 255) {
                throw new CommandException("tint components must be 0..255, got " + c);
            }
        }
        return (rgba[3] << 24) | (rgba[0] << 16) | (rgba[1] << 8) | rgba[2];
    }

    private Integer materialIdByName(String name) {
        if (name == null) return null;
        for (MaterialDefinition mat : doc.faceTextures().getAllMaterials()) {
            if (mat.name().equals(name)) return mat.materialId();
        }
        return null;
    }

    private int nextMaterialId() {
        int max = 0;
        for (MaterialDefinition mat : doc.faceTextures().getAllMaterials()) {
            max = Math.max(max, mat.materialId());
        }
        return max + 1;
    }

    private static Vector3f mirrorAxis(Vector3f v, int axis) {
        Vector3f out = new Vector3f(v);
        out.setComponent(axis, -out.get(axis));
        return out;
    }

    /** Mirroring across an axis plane negates the OTHER two Euler angles. */
    private static Vector3f mirrorRotation(Vector3f rotation, int axis) {
        Vector3f out = new Vector3f(rotation).negate();
        out.setComponent(axis, rotation.get(axis));
        return out;
    }

    private void trace(String opName, java.util.function.Consumer<ObjectNode> fill) {
        ObjectNode node = mapper.createObjectNode();
        node.put("op", opName);
        fill.accept(node);
        opsTrace.add(node);
    }

    private ArrayNode vecNode(Vector3f v) {
        ArrayNode n = mapper.createArrayNode();
        n.add(v.x).add(v.y).add(v.z);
        return n;
    }

    private ArrayNode intArrayNode(int[] values) {
        ArrayNode n = mapper.createArrayNode();
        for (int v : values) n.add(v);
        return n;
    }

    private ArrayNode floatArrayNode(float[] values) {
        ArrayNode n = mapper.createArrayNode();
        for (float v : values) n.add(v);
        return n;
    }

    private static String fmt(Vector3f v) {
        return "[" + v.x + ", " + v.y + ", " + v.z + "]";
    }
}
