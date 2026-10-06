package com.openmason.engine.cenda;

import com.openmason.engine.format.omui.UiValue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.cenda.LuaValueWriter.ARRAY;
import static com.openmason.engine.cenda.LuaValueWriter.F64;
import static com.openmason.engine.cenda.LuaValueWriter.FALSE;
import static com.openmason.engine.cenda.LuaValueWriter.I32;
import static com.openmason.engine.cenda.LuaValueWriter.I64;
import static com.openmason.engine.cenda.LuaValueWriter.INTEGER;
import static com.openmason.engine.cenda.LuaValueWriter.MAP;
import static com.openmason.engine.cenda.LuaValueWriter.NIL;
import static com.openmason.engine.cenda.LuaValueWriter.NUMBER;
import static com.openmason.engine.cenda.LuaValueWriter.STRING;
import static com.openmason.engine.cenda.LuaValueWriter.TRUE;

/**
 * Decodes a sequence of CL-encoded values ({@code lua_host.h}, ABI 2): the arguments of a
 * {@link LuaValueFunction} or the results of {@link LuaState#callValues}. A reader is reused by
 * its state (rebinding costs nothing), and is only valid until the next call on that state.
 *
 * <p>Typed reads ({@link #number()}, {@link #bool()}, {@link #isNil()}) allocate nothing;
 * {@link #string()} allocates the {@code String}, {@link #value()} the decoded tree.
 */
public final class LuaValueReader {

    /** Absolute view of the address space: reading by address needs no per-call segment. */
    private static final MemorySegment ALL = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

    private long base;
    private long length;
    private long pos;
    private int count;
    private int read;
    private byte[] scratch = new byte[64];

    void bind(long address, long byteLength, int values) {
        base = address;
        length = byteLength;
        pos = 0;
        count = values;
        read = 0;
    }

    /** Number of top-level values. */
    public int count() {
        return count;
    }

    /** Top-level values not yet consumed. */
    public int remaining() {
        return count - read;
    }

    /** Tag of the next value, or {@link LuaValueWriter#NIL} when every value was read (absent = nil). */
    public byte peek() {
        return read >= count ? NIL : ALL.get(ValueLayout.JAVA_BYTE, base + pos);
    }

    /** True when the next value is nil or absent. */
    public boolean isNil() {
        return peek() == NIL;
    }

    /** Skips the next value. */
    public void skip() {
        if (read >= count) {
            return;
        }
        read++;
        skipValue();
    }

    public boolean bool() {
        if (read >= count) {
            return false;
        }
        byte t = take();
        return switch (t) {
            case NIL, FALSE -> false;
            default -> {
                pos--;
                skipValue();
                yield true; // Lua truthiness
            }
        };
    }

    /** The next value as a number; NaN when it is not one. */
    public double number() {
        if (read >= count) {
            return Double.NaN;
        }
        byte t = take();
        switch (t) {
            case NUMBER -> {
                double d = ALL.get(F64, base + pos);
                pos += 8;
                return d;
            }
            case INTEGER -> {
                long l = ALL.get(I64, base + pos);
                pos += 8;
                return l;
            }
            default -> {
                pos--;
                skipValue();
                return Double.NaN;
            }
        }
    }

    /** The next value as a string, or {@code null} when it is not one. */
    public String string() {
        if (read >= count) {
            return null;
        }
        byte t = take();
        if (t != STRING) {
            pos--;
            skipValue();
            return null;
        }
        return readString();
    }

    /** The next value decoded as a UI value (nil → {@link UiValue#NULL}). */
    public UiValue value() {
        if (read >= count) {
            return UiValue.NULL;
        }
        read++;
        return decode(0);
    }

    // ── decoding ────────────────────────────────────────────────────────────

    private byte take() {
        read++;
        check(1);
        return ALL.get(ValueLayout.JAVA_BYTE, base + pos++);
    }

    private UiValue decode(int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("Lua value nested too deeply");
        }
        check(1);
        byte t = ALL.get(ValueLayout.JAVA_BYTE, base + pos++);
        return switch (t) {
            case NIL -> UiValue.NULL;
            case FALSE -> UiValue.FALSE;
            case TRUE -> UiValue.TRUE;
            case NUMBER -> {
                check(8);
                double d = ALL.get(F64, base + pos);
                pos += 8;
                if (!Double.isFinite(d)) {
                    throw new IllegalArgumentException("number must be finite, got " + d);
                }
                yield UiValue.of(d);
            }
            case INTEGER -> {
                check(8);
                long l = ALL.get(I64, base + pos);
                pos += 8;
                yield UiValue.of((double) l);
            }
            case STRING -> UiValue.of(readString());
            case ARRAY -> {
                int n = readLength();
                List<UiValue> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    items.add(decode(depth + 1));
                }
                yield new UiValue.Arr(items);
            }
            case MAP -> {
                int n = readLength();
                Map<String, UiValue> fields = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    check(1);
                    if (ALL.get(ValueLayout.JAVA_BYTE, base + pos) != STRING) {
                        throw new IllegalArgumentException("map key is not a string");
                    }
                    pos++;
                    String key = readString();
                    fields.put(key, decode(depth + 1));
                }
                yield new UiValue.Obj(fields);
            }
            default -> throw new IllegalArgumentException("Lua value cannot become a UI value (tag " + t + ")");
        };
    }

    private void skipValue() {
        check(1);
        byte t = ALL.get(ValueLayout.JAVA_BYTE, base + pos++);
        switch (t) {
            case NUMBER, INTEGER -> pos += 8;
            case STRING -> pos += readLength();
            case ARRAY -> {
                int n = readLength();
                for (int i = 0; i < n; i++) {
                    skipValue();
                }
            }
            case MAP -> {
                int n = readLength();
                for (int i = 0; i < 2 * n; i++) {
                    skipValue();
                }
            }
            case LuaValueWriter.REF -> pos += 4;
            default -> {
            }
        }
    }

    private int readLength() {
        check(4);
        int n = ALL.get(I32, base + pos);
        pos += 4;
        if (n < 0 || n > length - pos) {
            throw new IllegalArgumentException("malformed Lua value length " + n);
        }
        return n;
    }

    private String readString() {
        int n = readLength();
        if (scratch.length < n) {
            scratch = new byte[Math.max(n, scratch.length * 2)];
        }
        MemorySegment.copy(ALL, ValueLayout.JAVA_BYTE, base + pos, scratch, 0, n);
        pos += n;
        return new String(scratch, 0, n, StandardCharsets.UTF_8);
    }

    private void check(long n) {
        if (pos + n > length) {
            throw new IllegalArgumentException("truncated Lua value");
        }
    }
}
