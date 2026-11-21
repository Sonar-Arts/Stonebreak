package com.stonebreak.rendering.gameWorld.regions;

import com.openmason.engine.voxel.world.CubePos;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Open-addressing map from a full int64 cube triple {@code (x, y, z)} to a value, with allocation-free
 * lookups (no key objects): the renderer looks every visible cube of every frame up by the triples
 * {@code cv_visible_cubes} hands back, so a {@code HashMap<CubePos, V>} would allocate a key per cube per
 * frame. Keys are never packed into one long (the contract's rule); the hash is the engine's
 * {@link CubePos#hash} (chained polynomial + splitmix64 finaliser). Linear probing, backward-shift
 * deletion, load factor at most 1/2. Not thread-safe (GL thread).
 */
public final class CubeKeyMap<V> {

    private long[] keys;   // 3 per slot
    private Object[] values;
    private int mask;
    private int size;

    public CubeKeyMap() {
        this(64);
    }

    public CubeKeyMap(int expected) {
        int cap = Integer.highestOneBit(Math.max(16, expected * 2 - 1)) << 1;
        alloc(cap);
    }

    private void alloc(int cap) {
        keys = new long[cap * 3];
        values = new Object[cap];
        mask = cap - 1;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    private int slot(long x, long y, long z) {
        return (int) CubePos.hash(x, y, z) & mask;
    }

    @SuppressWarnings("unchecked")
    public V get(long x, long y, long z) {
        for (int i = slot(x, y, z); ; i = (i + 1) & mask) {
            Object v = values[i];
            if (v == null) {
                return null;
            }
            int k = i * 3;
            if (keys[k] == x && keys[k + 1] == y && keys[k + 2] == z) {
                return (V) v;
            }
        }
    }

    /** Puts {@code value} (non-null) and returns the previous value, or null. */
    @SuppressWarnings("unchecked")
    public V put(long x, long y, long z, V value) {
        if (value == null) {
            throw new IllegalArgumentException("null value");
        }
        if ((size + 1) * 2 > values.length) {
            rehash(values.length * 2);
        }
        for (int i = slot(x, y, z); ; i = (i + 1) & mask) {
            Object v = values[i];
            int k = i * 3;
            if (v == null) {
                keys[k] = x;
                keys[k + 1] = y;
                keys[k + 2] = z;
                values[i] = value;
                size++;
                return null;
            }
            if (keys[k] == x && keys[k + 1] == y && keys[k + 2] == z) {
                values[i] = value;
                return (V) v;
            }
        }
    }

    /** Removes the entry and returns its value, or null. */
    @SuppressWarnings("unchecked")
    public V remove(long x, long y, long z) {
        for (int i = slot(x, y, z); ; i = (i + 1) & mask) {
            Object v = values[i];
            if (v == null) {
                return null;
            }
            int k = i * 3;
            if (keys[k] == x && keys[k + 1] == y && keys[k + 2] == z) {
                deleteSlot(i);
                size--;
                return (V) v;
            }
        }
    }

    /** Backward-shift deletion: keeps every probe chain gap-free without tombstones. */
    private void deleteSlot(int hole) {
        int i = hole;
        for (;;) {
            i = (i + 1) & mask;
            Object v = values[i];
            if (v == null) {
                break;
            }
            int k = i * 3;
            int home = slot(keys[k], keys[k + 1], keys[k + 2]);
            // Move the entry back into the hole when its home is not in (hole, i] (cyclically).
            boolean inRange = hole <= i ? (home > hole && home <= i) : (home > hole || home <= i);
            if (!inRange) {
                int h = hole * 3;
                keys[h] = keys[k];
                keys[h + 1] = keys[k + 1];
                keys[h + 2] = keys[k + 2];
                values[hole] = v;
                hole = i;
            }
        }
        values[hole] = null;
    }

    public void clear() {
        Arrays.fill(values, null);
        size = 0;
    }

    @SuppressWarnings("unchecked")
    public void forEachValue(Consumer<? super V> action) {
        Object[] vs = values;
        for (Object v : vs) {
            if (v != null) {
                action.accept((V) v);
            }
        }
    }

    private void rehash(int cap) {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        alloc(cap);
        size = 0;
        for (int i = 0; i < oldValues.length; i++) {
            if (oldValues[i] != null) {
                int k = i * 3;
                put(oldKeys[k], oldKeys[k + 1], oldKeys[k + 2], (V) oldValues[i]);
            }
        }
    }
}
