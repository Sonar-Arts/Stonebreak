package com.openmason.engine.cearl;

/**
 * CEARL's type model. Small on purpose: primitives, fixed-size vectors,
 * user structs, and runtime-sized arrays (kernel buffer parameters only).
 *
 * <p>{@code i64} exists for the host side — sizes and {@code when}-guard
 * arithmetic in plans — and is rejected in device (kernel) code, where GLSL
 * 430 has no 64-bit integers without extensions. Everything else maps 1:1
 * onto a GLSL type via {@link #glslName()}.
 */
public sealed interface CearlType
        permits CearlType.Prim, CearlType.Vec, CearlType.Struct, CearlType.Array {

    enum PrimKind { BOOL, I32, U32, I64, F32, VOID }

    record Prim(PrimKind kind) implements CearlType {
    }

    /** {@code elem} is F32, I32, or U32; {@code size} 2..4. */
    record Vec(PrimKind elem, int size) implements CearlType {
    }

    /** Fields live in the checker's struct registry, keyed by this name. */
    record Struct(String name) implements CearlType {
    }

    record Array(CearlType elem) implements CearlType {
    }

    CearlType VOID = new Prim(PrimKind.VOID);
    CearlType BOOL = new Prim(PrimKind.BOOL);
    CearlType I32 = new Prim(PrimKind.I32);
    CearlType U32 = new Prim(PrimKind.U32);
    CearlType I64 = new Prim(PrimKind.I64);
    CearlType F32 = new Prim(PrimKind.F32);

    static CearlType vec(PrimKind elem, int size) {
        return new Vec(elem, size);
    }

    /** Resolves a builtin type name, or null when it isn't one (maybe a struct). */
    static CearlType builtin(String name) {
        return switch (name) {
            case "bool" -> BOOL;
            case "i32" -> I32;
            case "u32" -> U32;
            case "i64" -> I64;
            case "f32" -> F32;
            case "vec2" -> vec(PrimKind.F32, 2);
            case "vec3" -> vec(PrimKind.F32, 3);
            case "vec4" -> vec(PrimKind.F32, 4);
            case "ivec2" -> vec(PrimKind.I32, 2);
            case "ivec3" -> vec(PrimKind.I32, 3);
            case "ivec4" -> vec(PrimKind.I32, 4);
            case "uvec2" -> vec(PrimKind.U32, 2);
            case "uvec3" -> vec(PrimKind.U32, 3);
            case "uvec4" -> vec(PrimKind.U32, 4);
            default -> null;
        };
    }

    /** The CEARL-facing name, for diagnostics. */
    default String display() {
        return switch (this) {
            case Prim p -> switch (p.kind()) {
                case BOOL -> "bool";
                case I32 -> "i32";
                case U32 -> "u32";
                case I64 -> "i64";
                case F32 -> "f32";
                case VOID -> "void";
            };
            case Vec v -> (switch (v.elem()) {
                case F32 -> "vec";
                case I32 -> "ivec";
                case U32 -> "uvec";
                default -> "?vec";
            }) + v.size();
            case Struct s -> s.name();
            case Array a -> a.elem().display() + "[]";
        };
    }

    /** The GLSL spelling. Throws for host-only or non-GLSL types. */
    default String glslName() {
        return switch (this) {
            case Prim p -> switch (p.kind()) {
                case BOOL -> "bool";
                case I32 -> "int";
                case U32 -> "uint";
                case F32 -> "float";
                case VOID -> "void";
                case I64 -> throw new IllegalStateException("i64 has no GLSL form (host-only type)");
            };
            case Vec v -> (switch (v.elem()) {
                case F32 -> "vec";
                case I32 -> "ivec";
                case U32 -> "uvec";
                default -> throw new IllegalStateException("no GLSL vector of " + v.elem());
            }) + v.size();
            case Struct s -> s.name();
            case Array a -> throw new IllegalStateException("arrays are buffer blocks, not value types");
        };
    }

    default boolean isNumeric() {
        return this instanceof Prim p
            && (p.kind() == PrimKind.I32 || p.kind() == PrimKind.U32
                || p.kind() == PrimKind.I64 || p.kind() == PrimKind.F32);
    }

    default boolean isInteger() {
        return this instanceof Prim p
            && (p.kind() == PrimKind.I32 || p.kind() == PrimKind.U32 || p.kind() == PrimKind.I64);
    }

    default boolean isFloatVec() {
        return this instanceof Vec v && v.elem() == PrimKind.F32;
    }

    default boolean is(PrimKind kind) {
        return this instanceof Prim p && p.kind() == kind;
    }
}
