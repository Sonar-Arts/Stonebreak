package com.openmason.engine.cenda;

import com.openmason.engine.format.omui.UiValue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;

/**
 * Encodes values in the CL value encoding ({@code lua_host.h}, ABI 2) into reusable native
 * memory: arguments of {@link LuaState#callValues} and results of a {@link LuaValueFunction}.
 *
 * <p>Allocation-free in steady state: numbers, booleans and strings are written straight into
 * the segment (strings are UTF-8 encoded char by char), and the segment only grows, doubling,
 * when a value does not fit. A writer is owned by one {@link LuaState} and confined to its
 * thread.
 *
 * <p>Integral numbers within ±2<sup>53</sup> are written as Lua integers, so a script sees
 * {@code 3}, not {@code 3.0}, for a whole number the host sends.
 */
public final class LuaValueWriter {

    static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG_UNALIGNED;
    static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT_UNALIGNED;
    static final ValueLayout.OfDouble F64 = ValueLayout.JAVA_DOUBLE_UNALIGNED;

    public static final byte NIL = 0x00;
    public static final byte FALSE = 0x01;
    public static final byte TRUE = 0x02;
    public static final byte NUMBER = 0x03;
    public static final byte INTEGER = 0x04;
    public static final byte STRING = 0x05;
    public static final byte ARRAY = 0x06;
    public static final byte MAP = 0x07;
    public static final byte REF = 0x08;

    private static final double MAX_EXACT = 9007199254740992.0; // 2^53

    private final Arena arena;
    private MemorySegment segment;
    private long position;
    private int count;

    LuaValueWriter(Arena arena, long initialCapacity) {
        this.arena = arena;
        this.segment = arena.allocate(Math.max(64, initialCapacity), 8);
    }

    /** Starts a new value sequence. */
    public LuaValueWriter reset() {
        position = 0;
        count = 0;
        depth = 0;
        return this;
    }

    /** Top-level values written since {@link #reset()}. */
    public int count() {
        return count;
    }

    /** Encoded bytes written since {@link #reset()}. */
    public int length() {
        return (int) position;
    }

    /** The current backing memory; replaced (not resized) when the writer grows. */
    public MemorySegment segment() {
        return segment;
    }

    // ── values ──────────────────────────────────────────────────────────────

    public LuaValueWriter nil() {
        tag(NIL);
        return this;
    }

    public LuaValueWriter bool(boolean v) {
        tag(v ? TRUE : FALSE);
        return this;
    }

    /** A number; integral values within ±2^53 go as Lua integers. Non-finite values are refused. */
    public LuaValueWriter number(double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("Lua values from the host must be finite, got " + v);
        }
        if (v == Math.rint(v) && Math.abs(v) <= MAX_EXACT) {
            return integer((long) v);
        }
        tag(NUMBER);
        ensure(8);
        segment.set(F64, position, v);
        position += 8;
        return this;
    }

    public LuaValueWriter integer(long v) {
        tag(INTEGER);
        ensure(8);
        segment.set(I64, position, v);
        position += 8;
        return this;
    }

    /** A UTF-8 string (unpaired surrogates become {@code ?}). */
    public LuaValueWriter string(CharSequence s) {
        tag(STRING);
        int bytes = utf8Length(s);
        ensure(4L + bytes);
        segment.set(I32, position, bytes);
        position += 4;
        writeUtf8(s);
        return this;
    }

    /** A registry reference (host → Lua only): pushes the referenced value. */
    public LuaValueWriter ref(int registryRef) {
        tag(REF);
        ensure(4);
        segment.set(I32, position, registryRef);
        position += 4;
        return this;
    }

    /** Header of an array of {@code n} values; write exactly {@code n} values next, then {@link #end()}. */
    public LuaValueWriter array(int n) {
        tag(ARRAY);
        return length(n);
    }

    /** Header of a map of {@code n} entries; write {@code n} (string key, value) pairs, then {@link #end()}. */
    public LuaValueWriter map(int n) {
        tag(MAP);
        return length(n);
    }

    /** Any UI value; objects become string-keyed tables, arrays sequences. */
    public LuaValueWriter value(UiValue v) {
        switch (v) {
            case null -> nil();
            case UiValue.Null n -> nil();
            case UiValue.Bool b -> bool(b.value());
            case UiValue.Num n -> number(n.value());
            case UiValue.Str s -> string(s.value());
            case UiValue.Arr a -> {
                List<UiValue> items = a.items();
                array(items.size());
                for (UiValue item : items) {
                    value(item);
                }
                end();
            }
            case UiValue.Obj o -> {
                map(o.fields().size());
                for (Map.Entry<String, UiValue> e : o.fields().entrySet()) {
                    string(e.getKey());
                    value(e.getValue());
                }
                end();
            }
        }
        return this;
    }

    /** NUL-terminated raw bytes (the error message of a failing host function). */
    void cString(String message) {
        reset();
        int bytes = utf8Length(message);
        ensure(bytes + 1L);
        writeUtf8(message);
        segment.set(ValueLayout.JAVA_BYTE, position++, (byte) 0);
    }

    // ── encoding ────────────────────────────────────────────────────────────

    /** Open containers: values inside one are not top-level values. */
    private int depth;

    private void tag(byte t) {
        ensure(1);
        segment.set(ValueLayout.JAVA_BYTE, position++, t);
        if (depth == 0) {
            count++;
        }
    }

    private LuaValueWriter length(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("negative length " + n);
        }
        ensure(4);
        segment.set(I32, position, n);
        position += 4;
        depth++; // the elements that follow are nested values
        return this;
    }

    /** Closes the innermost {@link #array} or {@link #map}. */
    public LuaValueWriter end() {
        if (depth == 0) {
            throw new IllegalStateException("no open container");
        }
        depth--;
        return this;
    }

    private static int utf8Length(CharSequence s) {
        int n = 0;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                n += 1;
            } else {
                n += 3;
            }
        }
        return n;
    }

    private void writeUtf8(CharSequence s) {
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                put(c);
            } else if (c < 0x800) {
                put(0xC0 | (c >> 6));
                put(0x80 | (c & 0x3F));
            } else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                int cp = Character.toCodePoint(c, s.charAt(++i));
                put(0xF0 | (cp >> 18));
                put(0x80 | ((cp >> 12) & 0x3F));
                put(0x80 | ((cp >> 6) & 0x3F));
                put(0x80 | (cp & 0x3F));
            } else if (Character.isSurrogate(c)) {
                put('?');
            } else {
                put(0xE0 | (c >> 12));
                put(0x80 | ((c >> 6) & 0x3F));
                put(0x80 | (c & 0x3F));
            }
        }
    }

    private void put(int b) {
        segment.set(ValueLayout.JAVA_BYTE, position++, (byte) b);
    }

    private void ensure(long more) {
        long need = position + more;
        if (need <= segment.byteSize()) {
            return;
        }
        if (need > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Lua value larger than 2 GiB");
        }
        long size = segment.byteSize();
        while (size < need) {
            size *= 2;
        }
        MemorySegment grown = arena.allocate(size, 8);
        MemorySegment.copy(segment, 0, grown, 0, position);
        segment = grown;
    }
}
