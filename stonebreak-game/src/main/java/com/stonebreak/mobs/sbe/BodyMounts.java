package com.stonebreak.mobs.sbe;

import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.MountSet;
import com.openmason.engine.rig.SocketSet;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What hangs on one body's sockets (#459): hats, hair, earrings, a goblin's weapon, the held object. Each MOUNT is a
 * two-sided attachment resolved natively ({@link MountSet}, cenda/socket.h): a host socket by name, an {@code offset}
 * on it and the item's own {@code attach} point ({@code item frame = socket · offset · attach⁻¹}), plus named item
 * POINTS in item space (a torch's flame, a rod's tip, a staff's head). One mount per name; a mount without a visual is
 * not drawn by the attachment pass (the held object, which the held-item pass draws).
 *
 * <p>Owned by its body ({@link MountHost}); any thread may change it, the render thread resolves it: {@link #resolve}
 * rebuilds the native set when the mounts or the host's socket set changed, then writes every frame in one call.
 */
public final class BodyMounts {

    /**
     * One mount.
     *
     * @param name   unique per body (case-insensitive): a look slot ({@code hat}), a socket, {@code held}
     * @param socket the host socket it hangs on
     * @param visual what the attachment pass draws there; null = drawn elsewhere (the held object)
     * @param offset on the socket (null = identity)
     * @param attach the item's attach point, item space (null = its origin)
     * @param points named item points, item space (insertion order kept)
     */
    public record Mount(String name, String socket, AttachmentVisual visual, Matrix4fc offset, Matrix4fc attach,
                        Map<String, Matrix4fc> points) {
        public Mount {
            points = points == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(points));
            offset = offset == null ? null : new Matrix4f(offset);
            attach = attach == null ? null : new Matrix4f(attach);
        }

        /** A mount of {@code visual} straight on {@code socket}, named after it. */
        public static Mount on(String socket, AttachmentVisual visual) {
            return new Mount(socket, socket, visual, null, null, null);
        }

        String key() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    /** Current mounts (copy on write). */
    private volatile List<Mount> mounts = List.of();
    /** Bumped on every change. */
    private volatile int revision;

    // Render-thread state.
    private MountSet native_;
    private SocketSet boundSockets;
    private int boundRevision = -1;
    private List<Mount> bound = List.of();
    /** Per bound mount: its points' names, in native order. */
    private List<List<String>> boundPoints = List.of();

    /** Sets (or replaces, by name) a mount. */
    public synchronized void put(Mount mount) {
        List<Mount> next = new ArrayList<>(mounts.size() + 1);
        boolean replaced = false;
        for (Mount m : mounts) {
            if (m.key().equals(mount.key())) {
                next.add(mount);
                replaced = true;
            } else {
                next.add(m);
            }
        }
        if (!replaced) next.add(mount);
        mounts = List.copyOf(next);
        revision++;
    }

    /** Removes mount {@code name}; true when there was one. */
    public synchronized boolean remove(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        List<Mount> next = new ArrayList<>(mounts);
        boolean removed = next.removeIf(m -> m.key().equals(key));
        if (removed) {
            mounts = List.copyOf(next);
            revision++;
        }
        return removed;
    }

    /** Removes every mount; true when there was any. */
    public synchronized boolean clear() {
        if (mounts.isEmpty()) return false;
        mounts = List.of();
        revision++;
        return true;
    }

    /** The current mounts. */
    public List<Mount> list() {
        return mounts;
    }

    /** The mount named {@code name}, or null. */
    public Mount get(String name) {
        for (Mount m : mounts) {
            if (m.name().equalsIgnoreCase(name)) return m;
        }
        return null;
    }

    /** Whether any mount has a visual for the attachment pass. */
    public boolean hasVisuals() {
        for (Mount m : mounts) {
            if (m.visual() != null) return true;
        }
        return false;
    }

    /** What the held mount was last set from (the held-item pass's own bookkeeping), or null. */
    private volatile Object heldKey;

    public Object heldKey() {
        return heldKey;
    }

    public void setHeldKey(Object key) {
        heldKey = key;
    }

    /** A change counter (any change bumps it). */
    public int revision() {
        return revision;
    }

    /**
     * Render thread: resolves every mount on {@code palette} (the host posed into it; {@code sockets} = the host's
     * set; {@code pre}/{@code root} as {@link SocketSet#frame}). Returns the mounts in resolve order: read their frames
     * with {@link #frame} / {@link #point} by that index until the next resolve.
     */
    public List<Mount> resolve(SocketSet sockets, JointPalette palette, Matrix4fc pre, Matrix4fc root) {
        int rev = revision;
        if (native_ == null || sockets != boundSockets || rev != boundRevision) rebuild(sockets, rev);
        if (bound.isEmpty()) return bound;
        native_.resolve(sockets, palette, pre, root);
        return bound;
    }

    /** Mount {@code index}'s item frame from the last resolve, or null when it did not resolve. */
    public Matrix4f frame(int index, Matrix4f dest) {
        return native_ == null ? null : native_.frame(index, dest);
    }

    /** Point {@code name} (case-insensitive) of mount {@code index} from the last resolve, or null. */
    public Matrix4f point(int index, String name, Matrix4f dest) {
        int p = pointIndex(index, name);
        return p < 0 ? null : native_.point(index, p, dest);
    }

    /** The position of point {@code name} of mount {@code index} from the last resolve, or null. */
    public Vector3f pointPosition(int index, String name, Vector3f dest) {
        int p = pointIndex(index, name);
        return p < 0 ? null : native_.pointPosition(index, p, dest);
    }

    /** Index of mount {@code name} in the last resolve, or -1. */
    public int indexOf(String name) {
        List<Mount> b = bound;
        for (int i = 0; i < b.size(); i++) {
            if (b.get(i).name().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private int pointIndex(int index, String name) {
        if (native_ == null || index < 0 || index >= boundPoints.size()) return -1;
        List<String> names = boundPoints.get(index);
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private void rebuild(SocketSet sockets, int rev) {
        List<Mount> list = mounts;
        if (native_ == null) native_ = new MountSet();
        native_.clear();
        List<List<String>> points = new ArrayList<>(list.size());
        for (Mount m : list) {
            int index = native_.set(m.name(), sockets.find(m.socket()), m.offset(), m.attach());
            List<String> names = new ArrayList<>(m.points().size());
            for (Map.Entry<String, Matrix4fc> p : m.points().entrySet()) {
                native_.setPoint(index, p.getKey(), p.getValue());
                names.add(p.getKey());
            }
            points.add(List.copyOf(names));
        }
        bound = list;
        boundPoints = List.copyOf(points);
        boundSockets = sockets;
        boundRevision = rev;
    }
}
