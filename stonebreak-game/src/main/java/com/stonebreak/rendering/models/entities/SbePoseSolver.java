package com.stonebreak.rendering.models.entities;

import com.openmason.engine.format.oma.AnimLayering;
import com.openmason.engine.format.oma.AnimSampler;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.mobs.sbe.SbeAttachmentPoint;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.stonebreak.mobs.sbe.SbePart;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * GL-free SBE pose math: computes per-part world matrices from a base
 * transform, the rest pose and an {@link AnimState}, and resolves the world
 * frame of named attachment points (sockets).
 *
 * <p>This is the single source of the per-part transform pipeline — extracted
 * from {@link SbeEntityRenderer}, whose textured/wireframe/colored draw loops
 * all call {@link #forEachPartMatrix}, and reused by attachment rendering via
 * {@link #socketWorldMatrix} so attached models follow animation exactly.
 *
 * <p>Transform pipeline (part-local → world):
 * <pre>
 *   base       = T(position) · Ry(yaw) · S(scale)
 *   partMatrix = parent · M_anim · M_rest⁻¹      (parent = base, plus an extra
 *                neck-pivot rotation for the head part; un-animated parts use
 *                parent alone)
 *   M(pos,rot,scale,origin) = T(pos) · T(origin) · R_xyz(rot) · S(scale) · T(-origin)
 * </pre>
 * Socket positions/rotations/scales are authored in rest-pose model space (see
 * {@link SbeAttachmentPoint}), so:
 * <pre>
 *   socketWorld = partMatrix(host part) · T(localPos) · R_xyz(localRotDeg) · S(localScale)
 * </pre>
 * The socket therefore translates, rotates, AND scales the attached model —
 * the returned matrix is used directly as the attached model's base transform.
 */
public final class SbePoseSolver {

    /** Receives the computed world matrix for one animated part. */
    @FunctionalInterface
    public interface PartConsumer {
        void accept(Matrix4f partMatrix, SbePart part);
    }

    private SbePoseSolver() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** The base entity transform: {@code T(position) · Ry(yawDegrees) · S(scale)}. */
    public static Matrix4f baseMatrix(Vector3f position, float yawDegrees, Vector3f scale) {
        return new Matrix4f()
                .translate(position.x, position.y, position.z)
                .rotateY((float) Math.toRadians(yawDegrees))
                .scale(scale);
    }

    /**
     * Walks the geometry's parts, computing each part's world matrix from the
     * base transform plus its animation delta ({@code base * M_anim * M_rest^-1};
     * un-animated parts use {@code base} alone), and hands it to {@code consumer}.
     * When overlays are active, the per-part pose is composed via
     * {@link AnimLayering#blendPart} — masked parts blend toward the overlay's
     * pose by its weight; unmasked parts keep the base pose. {@code headYawDeg}/
     * {@code headPitchDeg} rotate the part named "head" about its neck pivot.
     */
    public static void forEachPartMatrix(SbeModelGeometry geometry, SbeEntityAsset asset,
                                         AnimState anim, Matrix4f base,
                                         float headYawDeg, float headPitchDeg,
                                         PartConsumer consumer) {
        ResolvedAnim resolved = resolveAnim(asset, anim);
        String headPartId = (headYawDeg != 0f || headPitchDeg != 0f) ? headPartId(geometry) : null;
        Matrix4f partMatrix = new Matrix4f();
        Matrix4f restInverse = new Matrix4f();
        for (SbePart part : geometry.parts()) {
            computePartMatrix(partMatrix, restInverse, part, resolved, base,
                    headPartId, headYawDeg, headPitchDeg);
            consumer.accept(partMatrix, part);
        }
    }

    /**
     * World matrix of the named socket (case-insensitive) at the given pose,
     * written into {@code dest} and returned — or {@code null} when the socket
     * is unknown, or when it names a host part that no longer exists in the
     * model (id and name fallback both miss), so callers skip rendering rather
     * than mount at the model origin.
     */
    public static Matrix4f socketWorldMatrix(SbeEntityAsset asset, String variantName,
                                             AnimState anim, Matrix4f base,
                                             float headYawDeg, float headPitchDeg,
                                             String socketName, Matrix4f dest) {
        if (asset == null || socketName == null) return null;
        SbeModelGeometry geometry = asset.geometryFor(variantName);
        if (geometry == null) return null;

        SbeAttachmentPoint socket = null;
        for (SbeAttachmentPoint candidate : geometry.attachmentPoints()) {
            if (socketName.equalsIgnoreCase(candidate.name())) {
                socket = candidate;
                break;
            }
        }
        if (socket == null) return null;

        if (socket.isModelRoot()) {
            dest.set(base);
        } else {
            SbePart host = resolveHostPart(geometry, socket);
            if (host == null) return null;
            ResolvedAnim resolved = resolveAnim(asset, anim);
            String headPartId = (headYawDeg != 0f || headPitchDeg != 0f) ? headPartId(geometry) : null;
            computePartMatrix(dest, new Matrix4f(), host, resolved, base,
                    headPartId, headYawDeg, headPitchDeg);
        }

        Vector3f pos = socket.localPos();
        Vector3f rot = socket.localRotDeg();
        Vector3f scl = socket.localScale();
        return dest
                .translate(pos)
                .rotateXYZ((float) Math.toRadians(rot.x),
                        (float) Math.toRadians(rot.y),
                        (float) Math.toRadians(rot.z))
                .scale(scl);
    }

    /** Host part by id, falling back to name; null when both miss. */
    private static SbePart resolveHostPart(SbeModelGeometry geometry, SbeAttachmentPoint socket) {
        if (socket.parentPartId() != null && !socket.parentPartId().isBlank()) {
            for (SbePart part : geometry.parts()) {
                if (socket.parentPartId().equals(part.id())) {
                    return part;
                }
            }
        }
        if (socket.parentPartName() != null && !socket.parentPartName().isBlank()) {
            for (SbePart part : geometry.parts()) {
                if (socket.parentPartName().equals(part.name())) {
                    return part;
                }
            }
        }
        return null;
    }

    /**
     * One part's world matrix into {@code dest}. {@code scratchRestInverse} is
     * caller-provided scratch so the per-frame loop stays allocation-free.
     */
    private static void computePartMatrix(Matrix4f dest, Matrix4f scratchRestInverse,
                                          SbePart part, ResolvedAnim resolved, Matrix4f base,
                                          String headPartId, float headYawDeg, float headPitchDeg) {
        ParsedAnimTrack track = resolved.baseTracks().get(part.id());
        if (track == null) {
            track = trackByName(resolved.baseClip(), part.name());
        }

        // The head part may receive an extra turn about its neck pivot, in the
        // model's local frame (between base and the part transform), so the head
        // can track the cursor while the body faces the movement direction.
        // This composes at the parent level and is orthogonal to pose layering.
        Matrix4f parent = base;
        if (headPartId != null && headPartId.equals(part.id())) {
            Vector3f rp = part.restPos();
            Vector3f ro = part.restOrigin();
            float px = rp.x + ro.x, py = rp.y + ro.y, pz = rp.z + ro.z; // pivot in model space
            parent = new Matrix4f(base)
                    .translate(px, py, pz)
                    .rotateY((float) Math.toRadians(headYawDeg))
                    .rotateX((float) Math.toRadians(headPitchDeg))
                    .translate(-px, -py, -pz);
        }

        AnimSampler.PartPose pose;
        if (!resolved.overlays().isEmpty()) {
            // Rest pose references the part's own vectors — no copies. blendPart
            // returns this same instance when nothing animates the part.
            AnimSampler.PartPose restPose = new AnimSampler.PartPose(
                    part.restPos(), part.restRot(), part.restScale());
            pose = AnimLayering.blendPart(restPose, track, resolved.baseTime(),
                    resolved.overlays(), part.id(), part.name());
            if (pose == restPose && track == null) {
                pose = null; // nothing touches this part — fast path below
            }
        } else {
            pose = track != null ? AnimSampler.sample(track, resolved.baseTime()) : null;
        }

        if (pose == null) {
            dest.set(parent);
        } else {
            Vector3f origin = part.restOrigin();

            // M_rest^-1
            partTransform(scratchRestInverse.identity(),
                    part.restPos(), part.restRot(), part.restScale(), origin)
                    .invert();
            // parent * M_anim * M_rest^-1
            partTransform(dest.set(parent),
                    pose.position(), pose.rotationDeg(), pose.scale(), origin)
                    .mul(scratchRestInverse);
        }
    }

    /**
     * A render-ready animation frame: the base clip with its wrapped time and
     * track lookup, plus the active overlay frames (clip + wrapped time +
     * effective weight) for {@link AnimLayering}.
     */
    private record ResolvedAnim(ParsedAnimClip baseClip, float baseTime,
                                Map<String, ParsedAnimTrack> baseTracks,
                                List<AnimLayering.OverlayFrame> overlays) {}

    /**
     * Resolve an {@link AnimState} against the asset's clips. Overlay weights
     * combine the caller's envelope with the clip's own fade-in/out
     * ({@link AnimLayering#clipWeight}); zero-weight or unknown overlays are
     * dropped so the per-part loop stays on its fast path.
     */
    private static ResolvedAnim resolveAnim(SbeEntityAsset asset, AnimState anim) {
        ParsedAnimClip baseClip = anim != null ? asset.clipFor(anim.baseState()) : null;
        float baseTime = 0f;
        Map<String, ParsedAnimTrack> baseTracks = Collections.emptyMap();
        if (baseClip != null) {
            baseTime = AnimSampler.wrapTime(anim.baseTime(), baseClip.duration(), baseClip.loop());
            baseTracks = baseClip.trackByPartId();
        }

        List<AnimLayering.OverlayFrame> overlays = List.of();
        if (anim != null && anim.hasOverlays()) {
            overlays = new ArrayList<>(anim.overlays().size());
            for (AnimState.Overlay overlay : anim.overlays()) {
                ParsedAnimClip clip = asset.clipFor(overlay.stateName());
                if (clip == null) continue;
                float weight = overlay.weight() * AnimLayering.clipWeight(clip, overlay.time());
                if (weight <= 0f) continue;
                float wrapped = AnimSampler.wrapTime(overlay.time(), clip.duration(), clip.loop());
                overlays.add(new AnimLayering.OverlayFrame(clip, wrapped, weight));
            }
        }
        return new ResolvedAnim(baseClip, baseTime, baseTracks, overlays);
    }

    /** One-time diagnostic guard so the head-part lookup logs at most once. */
    private static boolean headLookupLogged = false;

    /**
     * Part id of the geometry's head part (first part whose name contains "head",
     * case-insensitive), or {@code null} if none — in which case head tracking is a
     * no-op and the whole body simply uses the base yaw. Logs the resolution once.
     */
    private static String headPartId(SbeModelGeometry geometry) {
        String found = null;
        for (SbePart part : geometry.parts()) {
            String n = part.name();
            if (n != null && n.toLowerCase(java.util.Locale.ROOT).contains("head")) {
                found = part.id();
                break;
            }
        }
        if (!headLookupLogged) {
            headLookupLogged = true;
            StringBuilder names = new StringBuilder();
            for (SbePart part : geometry.parts()) {
                names.append(part.name()).append(' ');
            }
            System.out.println("[SbePoseSolver] Head-tracking part lookup: head="
                    + found + " parts=[" + names.toString().trim() + "]");
        }
        return found;
    }

    /**
     * Post-multiplies a part's local TRS transform onto {@code dest}:
     * {@code T(pos) * T(origin) * R(rot) * S(scale) * T(-origin)}, where
     * {@code rot} is Euler degrees and {@code origin} is the rotation pivot.
     */
    private static Matrix4f partTransform(Matrix4f dest, Vector3f pos, Vector3f rotDeg,
                                          Vector3f scale, Vector3f origin) {
        return dest
                .translate(pos)
                .translate(origin)
                .rotateXYZ((float) Math.toRadians(rotDeg.x),
                        (float) Math.toRadians(rotDeg.y),
                        (float) Math.toRadians(rotDeg.z))
                .scale(scale)
                .translate(-origin.x, -origin.y, -origin.z);
    }

    /** Fallback track lookup by part name when the part UUID does not match. */
    private static ParsedAnimTrack trackByName(ParsedAnimClip clip, String partName) {
        if (clip == null || partName == null) return null;
        for (ParsedAnimTrack track : clip.tracks()) {
            if (partName.equals(track.partName())) {
                return track;
            }
        }
        return null;
    }
}
