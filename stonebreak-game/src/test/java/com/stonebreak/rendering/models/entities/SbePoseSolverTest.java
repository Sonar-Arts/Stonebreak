package com.stonebreak.rendering.models.entities;

import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.openmason.engine.format.oma.ParsedKeyframe;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.mobs.sbe.SbeAttachmentPoint;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.stonebreak.mobs.sbe.SbePart;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for {@link SbePoseSolver}: part world matrices, socket
 * world frames (rest pose, animated, head turn), and host-part resolution
 * (id, name fallback, missing-parent skip).
 */
class SbePoseSolverTest {

    private static final float EPS = 1e-5f;

    private static SbePart part(String id, String name, Vector3f origin) {
        return new SbePart(id, name, null, origin,
                new Vector3f(), new Vector3f(), new Vector3f(1, 1, 1), List.of());
    }

    private static SbeModelGeometry geometry(List<SbePart> parts,
                                             List<SbeAttachmentPoint> sockets) {
        return new SbeModelGeometry(new float[0], new float[0], new int[0],
                parts, Map.of(), 0f, sockets);
    }

    private static SbeEntityAsset asset(SbeModelGeometry geometry,
                                        Map<String, ParsedAnimClip> clips) {
        return new SbeEntityAsset("test:asset",
                Map.of(SbeEntityAsset.DEFAULT_VARIANT, geometry), clips);
    }

    /** Constant-pose clip: every sample returns the given local transform. */
    private static ParsedAnimClip constantClip(String partId, String partName,
                                               Vector3f pos, Vector3f rotDeg) {
        ParsedKeyframe k0 = new ParsedKeyframe(0f, new Vector3f(pos), new Vector3f(rotDeg),
                new Vector3f(1, 1, 1), "LINEAR");
        ParsedKeyframe k1 = new ParsedKeyframe(1f, new Vector3f(pos), new Vector3f(rotDeg),
                new Vector3f(1, 1, 1), "LINEAR");
        return new ParsedAnimClip("TestClip", 24f, 1f, true,
                List.of(new ParsedAnimTrack(partId, partName, List.of(k0, k1))));
    }

    /** {@code T(pos) · T(origin) · R_xyz(rotDeg) · T(-origin)} onto {@code dest} (unit scale). */
    private static Matrix4f applyPartTransform(Matrix4f dest, Vector3f pos, Vector3f rotDeg,
                                               Vector3f origin) {
        return dest.translate(pos).translate(origin)
                .rotateXYZ((float) Math.toRadians(rotDeg.x),
                        (float) Math.toRadians(rotDeg.y),
                        (float) Math.toRadians(rotDeg.z))
                .translate(-origin.x, -origin.y, -origin.z);
    }

    private static Matrix4f applySocketLocal(Matrix4f dest, Vector3f pos, Vector3f rotDeg) {
        return dest.translate(pos)
                .rotateXYZ((float) Math.toRadians(rotDeg.x),
                        (float) Math.toRadians(rotDeg.y),
                        (float) Math.toRadians(rotDeg.z));
    }

    private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual) {
        assertTrue(expected.equals(actual, EPS),
                "expected:\n" + expected + "\nactual:\n" + actual);
    }

    @Test
    void unanimatedPartMatrixEqualsBase() {
        SbeModelGeometry g = geometry(List.of(part("p1", "body", new Vector3f())), List.of());
        SbeEntityAsset a = asset(g, Map.of());
        Matrix4f base = SbePoseSolver.baseMatrix(new Vector3f(3, 4, 5), 90f, new Vector3f(2, 2, 2));

        SbePoseSolver.forEachPartMatrix(g, a, null, base, 0f, 0f,
                (m, p) -> assertMatrixEquals(base, m));
    }

    @Test
    void socketOnUnanimatedPartIsBaseTimesLocalOffset() {
        Vector3f localPos = new Vector3f(0f, 1.5f, 0.25f);
        Vector3f localRot = new Vector3f(0f, 180f, 0f);
        SbeModelGeometry g = geometry(
                List.of(part("p1", "body", new Vector3f())),
                List.of(new SbeAttachmentPoint("a1", "face", "p1", "body", localPos, localRot)));
        SbeEntityAsset a = asset(g, Map.of());
        Matrix4f base = SbePoseSolver.baseMatrix(new Vector3f(10, 64, -3), 45f, new Vector3f(1, 1, 1));

        Matrix4f actual = SbePoseSolver.socketWorldMatrix(a, null, null, base, 0f, 0f,
                "face", new Matrix4f());
        assertNotNull(actual);
        Matrix4f expected = applySocketLocal(new Matrix4f(base), localPos, localRot);
        assertMatrixEquals(expected, actual);
    }

    @Test
    void socketNameLookupIsCaseInsensitive() {
        SbeModelGeometry g = geometry(
                List.of(part("p1", "body", new Vector3f())),
                List.of(new SbeAttachmentPoint("a1", "Face", "p1", "body",
                        new Vector3f(), new Vector3f())));
        SbeEntityAsset a = asset(g, Map.of());

        assertNotNull(SbePoseSolver.socketWorldMatrix(a, null, null, new Matrix4f(),
                0f, 0f, "FACE", new Matrix4f()));
    }

    @Test
    void socketFollowsAnimatedHostPart() {
        Vector3f origin = new Vector3f(1f, 2f, 3f);
        Vector3f animPos = new Vector3f(0.5f, 0f, 0f);
        Vector3f animRot = new Vector3f(0f, 90f, 0f);
        Vector3f localPos = new Vector3f(0f, 0.5f, 1f);
        Vector3f localRot = new Vector3f(15f, 0f, 0f);

        // Identity rest pose (restPos 0, restRot 0, restScale 1) => M_rest = I,
        // so partMatrix = base · M_anim.
        SbeModelGeometry g = geometry(
                List.of(part("p1", "body", origin)),
                List.of(new SbeAttachmentPoint("a1", "saddle", "p1", "body", localPos, localRot)));
        SbeEntityAsset a = asset(g, Map.of("Walk", constantClip("p1", "body", animPos, animRot)));
        Matrix4f base = SbePoseSolver.baseMatrix(new Vector3f(2, 0, -7), 30f, new Vector3f(1, 1, 1));

        Matrix4f actual = SbePoseSolver.socketWorldMatrix(a, null,
                AnimState.single("Walk", 0.25f), base, 0f, 0f, "saddle", new Matrix4f());
        assertNotNull(actual);

        Matrix4f expected = applyPartTransform(new Matrix4f(base), animPos, animRot, origin);
        applySocketLocal(expected, localPos, localRot);
        assertMatrixEquals(expected, actual);
    }

    @Test
    void socketScaleIsAppliedToTheAttachedFrame() {
        Vector3f localPos = new Vector3f(0f, 1f, 0f);
        Vector3f localRot = new Vector3f(0f, 90f, 0f);
        Vector3f localScale = new Vector3f(0.5f, 2f, 0.5f);
        SbeModelGeometry g = geometry(
                List.of(part("p1", "head", new Vector3f())),
                List.of(new SbeAttachmentPoint("a1", "hat", "p1", "head",
                        localPos, localRot, localScale)));
        SbeEntityAsset a = asset(g, Map.of());
        Matrix4f base = SbePoseSolver.baseMatrix(new Vector3f(4, 5, 6), 30f, new Vector3f(1, 1, 1));

        Matrix4f actual = SbePoseSolver.socketWorldMatrix(a, null, null, base, 0f, 0f,
                "hat", new Matrix4f());
        assertNotNull(actual);
        Matrix4f expected = applySocketLocal(new Matrix4f(base), localPos, localRot)
                .scale(localScale);
        assertMatrixEquals(expected, actual);
    }

    @Test
    void conveniencePointConstructorDefaultsToUnitScale() {
        SbeAttachmentPoint p = new SbeAttachmentPoint("a1", "s", null, null,
                new Vector3f(), new Vector3f());
        assertTrue(new Vector3f(1, 1, 1).equals(p.localScale(), EPS));
    }

    @Test
    void hostPartResolvesByNameWhenIdMisses() {
        SbeModelGeometry g = geometry(
                List.of(part("fresh-uuid", "head", new Vector3f())),
                List.of(new SbeAttachmentPoint("a1", "hat", "stale-uuid", "head",
                        new Vector3f(0, 1, 0), new Vector3f())));
        SbeEntityAsset a = asset(g, Map.of());
        Matrix4f base = new Matrix4f();

        Matrix4f actual = SbePoseSolver.socketWorldMatrix(a, null, null, base, 0f, 0f,
                "hat", new Matrix4f());
        assertNotNull(actual);
        assertMatrixEquals(new Matrix4f().translate(0, 1, 0), actual);
    }

    @Test
    void unknownSocketReturnsNull() {
        SbeModelGeometry g = geometry(List.of(part("p1", "body", new Vector3f())), List.of());
        SbeEntityAsset a = asset(g, Map.of());
        assertNull(SbePoseSolver.socketWorldMatrix(a, null, null, new Matrix4f(),
                0f, 0f, "nope", new Matrix4f()));
    }

    @Test
    void missingSpecifiedParentReturnsNullInsteadOfRootFallback() {
        SbeModelGeometry g = geometry(
                List.of(part("p1", "body", new Vector3f())),
                List.of(new SbeAttachmentPoint("a1", "orphan", "gone-id", "gone-name",
                        new Vector3f(), new Vector3f())));
        SbeEntityAsset a = asset(g, Map.of());
        assertNull(SbePoseSolver.socketWorldMatrix(a, null, null, new Matrix4f(),
                0f, 0f, "orphan", new Matrix4f()));
    }

    @Test
    void modelRootSocketUsesBaseFrame() {
        Vector3f localPos = new Vector3f(1f, 2f, 3f);
        SbeModelGeometry g = geometry(
                List.of(part("p1", "body", new Vector3f())),
                List.of(new SbeAttachmentPoint("a1", "flag", null, null,
                        localPos, new Vector3f(0, 90, 0))));
        SbeEntityAsset a = asset(g, Map.of());
        Matrix4f base = SbePoseSolver.baseMatrix(new Vector3f(5, 6, 7), 120f, new Vector3f(2, 2, 2));

        Matrix4f actual = SbePoseSolver.socketWorldMatrix(a, null, null, base, 0f, 0f,
                "flag", new Matrix4f());
        assertNotNull(actual);
        Matrix4f expected = applySocketLocal(new Matrix4f(base), localPos, new Vector3f(0, 90, 0));
        assertMatrixEquals(expected, actual);
    }

    @Test
    void socketOnHeadPartPicksUpHeadTurn() {
        // Head part with a rest pos + origin so the neck pivot is off-origin.
        Vector3f restPos = new Vector3f(0f, 1f, 0f);
        Vector3f restOrigin = new Vector3f(0f, 0.25f, 0f);
        SbePart head = new SbePart("h1", "head", null, restOrigin,
                restPos, new Vector3f(), new Vector3f(1, 1, 1), List.of());
        Vector3f localPos = new Vector3f(0f, 1.4f, 0.3f);
        SbeModelGeometry g = geometry(
                List.of(head),
                List.of(new SbeAttachmentPoint("a1", "face", "h1", "head",
                        localPos, new Vector3f())));
        SbeEntityAsset a = asset(g, Map.of());
        Matrix4f base = SbePoseSolver.baseMatrix(new Vector3f(1, 2, 3), 10f, new Vector3f(1, 1, 1));

        float headYaw = 40f, headPitch = -20f;
        Matrix4f actual = SbePoseSolver.socketWorldMatrix(a, null, null, base,
                headYaw, headPitch, "face", new Matrix4f());
        assertNotNull(actual);

        // parent = base · T(pivot) · Ry(headYaw) · Rx(headPitch) · T(-pivot);
        // un-animated head => partMatrix = parent; socket = parent · T(localPos).
        float px = restPos.x + restOrigin.x, py = restPos.y + restOrigin.y,
                pz = restPos.z + restOrigin.z;
        Matrix4f expected = new Matrix4f(base)
                .translate(px, py, pz)
                .rotateY((float) Math.toRadians(headYaw))
                .rotateX((float) Math.toRadians(headPitch))
                .translate(-px, -py, -pz)
                .translate(localPos);
        assertMatrixEquals(expected, actual);
    }
}
