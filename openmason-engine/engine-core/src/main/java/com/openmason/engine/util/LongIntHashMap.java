package com.openmason.engine.util;

import java.util.function.LongPredicate;

/**
 * Minimal open-addressing hash map from primitive {@code long} keys to primitive {@code int}
 * values. Exists to replace {@code Map<Long, Integer>} on hot per-tick paths (e.g. per-chunk
 * version tracking keyed by packed chunk coordinates), where every boxed lookup allocates a
 * fresh {@code Long} — packed keys fall far outside the {@code Long} cache.
 *
 * <p>Linear probing with backward-shift deletion (no tombstones), power-of-two capacity,
 * 0.5 max load factor. Key {@code 0} is the internal FREE sentinel, so the zero key is
 * tracked out-of-band and remains fully supported.
 *
 * <p>Not thread-safe; callers confine instances to a single thread (as the server tick
 * thread does).
 */
public final class LongIntHashMap {

    private static final long FREE = 0L;
    private static final int MIN_CAPACITY = 16;

    private long[] keys;
    private int[] values;
    private int mask;
    private int size;

    private boolean hasZeroKey;
    private int zeroValue;

    public LongIntHashMap() {
        this(MIN_CAPACITY);
    }

    /** @param expectedEntries sizing hint; the table starts large enough to hold this many without rehashing. */
    public LongIntHashMap(int expectedEntries) {
        int capacity = MIN_CAPACITY;
        while (capacity < expectedEntries * 2) {
            capacity <<= 1;
        }
        keys = new long[capacity];
        values = new int[capacity];
        mask = capacity - 1;
    }

    /** Returns the value mapped to {@code key}, or {@code defaultValue} if absent. Never allocates. */
    public int get(long key, int defaultValue) {
        if (key == 0L) {
            return hasZeroKey ? zeroValue : defaultValue;
        }
        int idx = indexFor(key);
        while (keys[idx] != FREE) {
            if (keys[idx] == key) {
                return values[idx];
            }
            idx = (idx + 1) & mask;
        }
        return defaultValue;
    }

    public void put(long key, int value) {
        if (key == 0L) {
            if (!hasZeroKey) {
                hasZeroKey = true;
                size++;
            }
            zeroValue = value;
            return;
        }
        int idx = indexFor(key);
        while (keys[idx] != FREE) {
            if (keys[idx] == key) {
                values[idx] = value;
                return;
            }
            idx = (idx + 1) & mask;
        }
        keys[idx] = key;
        values[idx] = value;
        size++;
        if ((size - (hasZeroKey ? 1 : 0)) * 2 > keys.length) {
            rehash(keys.length << 1);
        }
    }

    public void remove(long key) {
        if (key == 0L) {
            if (hasZeroKey) {
                hasZeroKey = false;
                size--;
            }
            return;
        }
        int idx = indexFor(key);
        while (keys[idx] != FREE) {
            if (keys[idx] == key) {
                removeSlot(idx);
                size--;
                return;
            }
            idx = (idx + 1) & mask;
        }
    }

    /** Removes every entry whose key matches the predicate. Never allocates. */
    public void removeIf(LongPredicate predicate) {
        if (hasZeroKey && predicate.test(0L)) {
            hasZeroKey = false;
            size--;
        }
        // Backward-shift deletion can relocate an entry across the array-end wrap to a slot
        // this pass already visited; repeat until a full pass removes nothing so such an
        // entry can't escape the predicate.
        boolean removedAny;
        do {
            removedAny = false;
            for (int i = 0; i < keys.length; i++) {
                long k = keys[i];
                if (k != FREE && predicate.test(k)) {
                    removeSlot(i);
                    size--;
                    removedAny = true;
                    i--; // the shift may have pulled a not-yet-visited entry into slot i
                }
            }
        } while (removedAny);
    }

    public void clear() {
        java.util.Arrays.fill(keys, FREE);
        hasZeroKey = false;
        size = 0;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    private int indexFor(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return (int) h & mask;
    }

    /** Backward-shift deletion: close the probe chain over the freed slot, no tombstones. */
    private void removeSlot(int pos) {
        int last = pos;
        int idx = (pos + 1) & mask;
        while (keys[idx] != FREE) {
            int ideal = indexFor(keys[idx]);
            // Move keys[idx] into the hole iff its ideal slot lies at or before the hole in
            // circular probe order — otherwise moving it would break its own probe chain.
            if (((idx - ideal) & mask) >= ((idx - last) & mask)) {
                keys[last] = keys[idx];
                values[last] = values[idx];
                last = idx;
            }
            idx = (idx + 1) & mask;
        }
        keys[last] = FREE;
    }

    private void rehash(int newCapacity) {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[newCapacity];
        values = new int[newCapacity];
        mask = newCapacity - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != FREE) {
                int idx = indexFor(oldKeys[i]);
                while (keys[idx] != FREE) {
                    idx = (idx + 1) & mask;
                }
                keys[idx] = oldKeys[i];
                values[idx] = oldValues[i];
            }
        }
    }
}
