package com.openmason.engine.rendering.model.gmr.editable.ops;

import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Topologically merge vertices into one (Blender's merge / the tool's
 * drag-vertex-onto-vertex commit): every face loop referencing a merged
 * vertex is rewritten to the kept vertex, edges between merged vertices
 * collapse, and faces that degenerate below 3 distinct vertices are removed.
 *
 * <p>This is a REAL merge — faces genuinely share the kept vertex afterward.
 * The legacy pipeline only got this implicitly from position re-welding;
 * {@link EditableMesh} never welds after import, so merging is an explicit op.
 *
 * <p>Positions are left untouched: callers position the vertices before
 * merging (the drag already moved them together). Merged vertices become
 * orphans (never referenced again).
 */
public final class MergeVerticesOp {

    /**
     * @param keptVertexId    The surviving vertex
     * @param rewrittenFaceIds Faces whose loops now reference the kept vertex
     * @param droppedFaceIds  Faces removed because they degenerated (the
     *                        caller must drop their texture mappings)
     */
    public record Result(int keptVertexId, int[] rewrittenFaceIds, int[] droppedFaceIds) {
    }

    private MergeVerticesOp() {
        // Static op — no instantiation
    }

    /**
     * @param mesh            Mesh to mutate
     * @param keepVertexId    Vertex that survives
     * @param mergedVertexIds Vertices to merge into it (kept id tolerated and ignored)
     * @return Result, or {@code null} if the inputs are invalid or a no-op
     */
    public static Result apply(EditableMesh mesh, int keepVertexId, int[] mergedVertexIds) {
        if (!mesh.isValidVertex(keepVertexId) || mergedVertexIds == null) {
            return null;
        }
        Set<Integer> merged = new HashSet<>();
        for (int v : mergedVertexIds) {
            if (v != keepVertexId && mesh.isValidVertex(v)) {
                merged.add(v);
            }
        }
        if (merged.isEmpty()) {
            return null;
        }

        // Collect affected faces first — rewriting mutates loops while iterating.
        Set<EditableFace> affected = new LinkedHashSet<>();
        for (int v : merged) {
            affected.addAll(mesh.facesWithVertex(v));
        }

        List<Integer> rewritten = new ArrayList<>();
        List<Integer> dropped = new ArrayList<>();

        for (EditableFace face : affected) {
            int[] loop = face.loop();
            float[] uvs = face.cornerUVs();

            // Rewrite merged ids to the kept id, then collapse consecutive
            // duplicates (wrap-aware) — a collapsed edge removes one corner;
            // the first occurrence keeps its authored UV.
            List<Integer> newLoop = new ArrayList<>(loop.length);
            List<float[]> newUVs = uvs != null ? new ArrayList<>(loop.length) : null;
            for (int i = 0; i < loop.length; i++) {
                int v = merged.contains(loop[i]) ? keepVertexId : loop[i];
                if (!newLoop.isEmpty() && newLoop.get(newLoop.size() - 1) == v) {
                    continue;
                }
                newLoop.add(v);
                if (newUVs != null) {
                    newUVs.add(new float[]{uvs[i * 2], uvs[i * 2 + 1]});
                }
            }
            // Wrap-around duplicate (last == first).
            while (newLoop.size() > 1 && newLoop.get(0).equals(newLoop.get(newLoop.size() - 1))) {
                newLoop.remove(newLoop.size() - 1);
                if (newUVs != null) {
                    newUVs.remove(newUVs.size() - 1);
                }
            }

            // Non-adjacent repeats (bowtie after merge) or too few vertices → drop.
            Set<Integer> distinct = new HashSet<>(newLoop);
            if (newLoop.size() < 3 || distinct.size() != newLoop.size()) {
                mesh.removeFace(face.faceId());
                dropped.add(face.faceId());
                continue;
            }

            int[] loopArr = new int[newLoop.size()];
            for (int i = 0; i < loopArr.length; i++) {
                loopArr[i] = newLoop.get(i);
            }
            float[] uvArr = null;
            if (newUVs != null) {
                uvArr = new float[newUVs.size() * 2];
                for (int i = 0; i < newUVs.size(); i++) {
                    uvArr[i * 2] = newUVs.get(i)[0];
                    uvArr[i * 2 + 1] = newUVs.get(i)[1];
                }
            }
            mesh.replaceFaceLoop(face.faceId(), loopArr, uvArr);
            rewritten.add(face.faceId());
        }

        return new Result(keepVertexId, toIntArray(rewritten), toIntArray(dropped));
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
