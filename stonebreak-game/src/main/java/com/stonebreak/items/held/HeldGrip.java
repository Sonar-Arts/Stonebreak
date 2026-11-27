package com.stonebreak.items.held;

import com.stonebreak.gameplay.SboGameplay;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a held object sits on a character (#458): the socket it hangs from and its grip there, {@code T·R·S} in the
 * socket frame (the socket convention: translate, XYZ Euler degrees, uniform scale). The object's canonical held
 * frame ({@link HeldKind}) is mapped by {@code socketFrame · local}. Since #459 it also carries the object's named
 * POINTS (a torch's {@code flame}, a rod's {@code tip}, a staff's {@code head}) in that canonical frame: the held
 * object is a two-sided mount ({@link com.stonebreak.mobs.sbe.BodyMounts}), resolved natively.
 *
 * @param socket the character socket ({@code grip_r}, {@code grip_l}, ...)
 * @param local  the grip in the socket frame
 * @param points named points of the object, canonical held frame (insertion order kept)
 */
public record HeldGrip(String socket, Matrix4fc local, Map<String, Matrix4fc> points) {

    public HeldGrip {
        local = new Matrix4f(local);
        LinkedHashMap<String, Matrix4fc> copy = new LinkedHashMap<>();
        if (points != null) points.forEach((k, v) -> copy.put(k, new Matrix4f(v)));
        points = Collections.unmodifiableMap(copy);
    }

    /** A grip without points. */
    public HeldGrip(String socket, Matrix4fc local) {
        this(socket, local, null);
    }

    /** The grip an SBO {@code held} record (or a defaults-file entry) describes. */
    public static HeldGrip of(SboGameplay.HeldData data) {
        LinkedHashMap<String, Matrix4fc> points = new LinkedHashMap<>();
        data.points().forEach((name, p) -> points.put(name, trs(p.position(), p.rotation(), 1f)));
        return new HeldGrip(data.socket(), trs(data.position(), data.rotation(), data.scale()), points);
    }

    /** {@code T(pos) · Rxyz(rot degrees) · S(scale)}. */
    static Matrix4f trs(SboGameplay.Vec3 pos, SboGameplay.Vec3 rot, float scale) {
        return new Matrix4f()
                .translate(pos.x(), pos.y(), pos.z())
                .rotateXYZ((float) Math.toRadians(rot.x()), (float) Math.toRadians(rot.y()),
                        (float) Math.toRadians(rot.z()))
                .scale(scale);
    }

    /** This grip with {@code points} instead. */
    public HeldGrip withPoints(Map<String, Matrix4fc> points) {
        return new HeldGrip(socket, local, points);
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
