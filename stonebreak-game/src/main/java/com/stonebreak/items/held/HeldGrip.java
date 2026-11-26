package com.stonebreak.items.held;

import com.stonebreak.gameplay.SboGameplay;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Where a held object sits on a character (#458): the socket it hangs from and its grip there, {@code T·R·S} in the
 * socket frame (the socket convention: translate, XYZ Euler degrees, uniform scale). The object's canonical held
 * frame ({@link HeldKind}) is mapped by {@code socketFrame · local}.
 *
 * @param socket the character socket ({@code grip_r}, {@code grip_l}, ...)
 * @param local  the grip in the socket frame
 */
public record HeldGrip(String socket, Matrix4fc local) {

    public HeldGrip {
        local = new Matrix4f(local);
    }

    /** The grip an SBO {@code held} record (or a defaults-file entry) describes. */
    public static HeldGrip of(SboGameplay.HeldData data) {
        return new HeldGrip(data.socket(), new Matrix4f()
                .translate(data.position().x(), data.position().y(), data.position().z())
                .rotateXYZ((float) Math.toRadians(data.rotation().x()),
                        (float) Math.toRadians(data.rotation().y()),
                        (float) Math.toRadians(data.rotation().z()))
                .scale(data.scale()));
    }

    /** {@code socketFrame · local} into {@code dest}: the held object's canonical frame. */
    public Matrix4f place(Matrix4fc socketFrame, Matrix4f dest) {
        return socketFrame.mul(local, dest);
    }

    /** A point of the object's canonical frame under {@code socketFrame}. */
    public Vector3f point(Matrix4fc socketFrame, Vector3f localPoint, Vector3f dest) {
        Matrix4f m = place(socketFrame, new Matrix4f());
        return m.transformPosition(localPoint, dest);
    }
}
