package com.stonebreak.mobs.sbe;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.entity.model.EntityAttachmentPoint;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.RigPoser;
import com.openmason.engine.rig.SocketSet;
import com.stonebreak.player.PlayerCharacter;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #459: cosmetics hang on the host through native mounts. A hat on SB_Player's {@code Hatzone} lands exactly where
 * the old Java lookup put it (K(host) · T·R·S of the OMO attachment point, frozen below), on the model-space palette
 * with the base as {@code pre}; the player's sockets carry both its OMO points and its {@code .omchar} sockets; a
 * mount on a missing socket is skipped; a model worn by its own {@code mount} point is placed so that point sits on
 * the socket.
 */
class BodyMountsTest {

    @BeforeAll
    static void native_() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda native kernels unavailable");
    }

    static Matrix4f legacy(EntityGeometry g, JointPalette modelSpace, Matrix4f base, String name) {
        for (EntityAttachmentPoint p : g.attachmentPoints()) {
            if (!p.name().equalsIgnoreCase(name)) continue;
            int host = g.rig().jointOf(p.parentPartId());
            Matrix4f k = modelSpace.skinMatrix(host, new Matrix4f());
            return new Matrix4f(base).mul(k).translate(p.localPos())
                    .rotateXYZ((float) Math.toRadians(p.localRotDeg().x), (float) Math.toRadians(p.localRotDeg().y),
                            (float) Math.toRadians(p.localRotDeg().z))
                    .scale(p.localScale());
        }
        throw new AssertionError("no " + name);
    }

    @Test
    void aHatSitsWhereTheOldLookupPutIt() {
        EntityAsset player = PlayerCharacter.get().asset();
        EntityGeometry g = player.geometryFor(EntityAsset.DEFAULT_VARIANT);
        SocketSet sockets = HostSockets.of(player, null);
        for (String name : List.of("Hatzone", "Hair", "Earring", "grip_r", "grip_l", "eyes", "back")) {
            assertTrue(sockets.find(name) >= 0, "player socket " + name);
        }

        float[] locals = new float[g.rig().jointCount() * 9];
        JointPalette rest = new JointPalette();
        RigPoser.local().poseLocals(g.rig().rig(), null, null, rest);
        RigPoser.local().localPose(g.rig().rig(), null, 0f, List.of(), locals);
        for (int j = 0; j < g.rig().jointCount(); j++) locals[j * 9 + 4] += 20f;  // a yaw on every joint
        JointPalette posed = new JointPalette();
        RigPoser.local().poseLocals(g.rig().rig(), locals, null, posed);
        Matrix4f base = new Matrix4f().translate(4f, 70f, -9f).rotateY(0.8f);

        BodyMounts mounts = new BodyMounts();
        mounts.put(new BodyMounts.Mount("hat", "Hatzone", null, null, null, null));
        mounts.put(new BodyMounts.Mount("nothing", "no_such_socket", null, null, null, null));
        List<BodyMounts.Mount> resolved = mounts.resolve(sockets, posed, base, base);
        assertEquals(2, resolved.size());
        Matrix4f hat = mounts.frame(mounts.indexOf("hat"), new Matrix4f());
        assertNotNull(hat);
        assertTrue(hat.equals(legacy(g, posed, base, "Hatzone"), 2e-5f), "hat frame == the old lookup");
        assertNull(mounts.frame(mounts.indexOf("nothing"), new Matrix4f()), "a missing socket never resolves");

        // Worn by its own attach point: that point lands on the socket.
        Matrix4f attach = new Matrix4f().translate(0f, -0.2f, 0.05f).rotateY(0.3f);
        mounts.put(new BodyMounts.Mount("hat", "Hatzone", null, null, attach, null));
        mounts.resolve(sockets, posed, base, base);
        Matrix4f worn = mounts.frame(mounts.indexOf("hat"), new Matrix4f());
        assertTrue(worn.mul(attach, new Matrix4f()).equals(legacy(g, posed, base, "Hatzone"), 2e-5f));
        Vector3f unused = new Vector3f();
        assertNull(mounts.pointPosition(mounts.indexOf("hat"), "tip", unused), "no such point");
    }
}
