package com.openmason.engine.cearl;

import java.util.List;

/**
 * The CEARL abstract syntax tree — every node the parser produces, as nested
 * records. Two node families share one expression language:
 *
 * <ul>
 *   <li><b>Code</b>: {@code struct} (with methods — statically dispatched OOP),
 *       {@code fn}, {@code kernel}, {@code const} — type-checked by
 *       {@link CearlChecker} and lowered to GLSL compute by {@link GlslEmitter}.
 *   <li><b>Plan</b>: {@code plan}/{@code pool}/{@code when}/{@code on pressure}
 *       — resource directives resolved at compile time into a
 *       {@code com.openmason.engine.vram.VramPlan}.
 * </ul>
 *
 * <p>Unset optional fields use sentinels ({@code -1}, {@code null},
 * {@code Integer.MIN_VALUE}) rather than Optionals — pool inheritance and
 * {@code when}-block overlays merge drafts field-by-field, and a sentinel is
 * what "this draft says nothing about that field" looks like.
 */
public final class CearlAst {

    private CearlAst() {
    }

    public record Program(List<StructDecl> structs, List<FnDecl> fns, List<KernelDecl> kernels,
                          List<ConstDecl> consts, PlanDecl plan) {
    }

    // ─── Code declarations ────────────────────────────────────────────────

    public record ConstDecl(String name, TypeRef declared, Expr value, int line) {
    }

    public record StructDecl(String name, List<Field> fields, List<FnDecl> methods, int line) {
    }

    public record Field(String name, TypeRef type, int line) {
    }

    /**
     * A free function, or a struct method when {@code owner} is non-null
     * (methods see the receiver as the implicit {@code self}).
     */
    public record FnDecl(String name, String owner, List<Param> params, TypeRef ret,
                         Block body, int line) {
    }

    public record KernelDecl(String name, int localSize, List<Param> params,
                             List<SharedDecl> shareds, Block body, int line) {
    }

    /**
     * Workgroup-shared memory: {@code shared scratch: u32[256]} — a fixed-size
     * array every thread in the workgroup reads and writes, synchronized with
     * {@code barrier()}. The mechanic behind reductions and scans.
     */
    public record SharedDecl(String name, TypeRef type, int line) {
    }

    public enum Dir { IN, OUT, INOUT }

    public record Param(String name, TypeRef type, Dir dir, int line) {
    }

    /**
     * A syntactic type reference; resolution happens in the checker.
     * {@code size} is non-null for sized arrays ({@code vec4[6]},
     * {@code u32[SLOTS]}) — a const expression evaluated at compile time.
     * Sized arrays are legal as kernel uniform-array takes and shared
     * declarations; unsized arrays only as kernel buffer takes.
     */
    public record TypeRef(String name, boolean array, Expr size, int line) {
        public String display() {
            return name + (array ? (size != null ? "[n]" : "[]") : "");
        }
    }

    // ─── Statements ───────────────────────────────────────────────────────

    public sealed interface Stmt permits Block, Let, Assign, If, While, ForRange,
            Return, Break, Continue, ExprStmt {
        int line();
    }

    public record Block(List<Stmt> stmts, int line) implements Stmt {
    }

    /** {@code let} (immutable) or {@code var} (mutable) local declaration. */
    public record Let(String name, TypeRef declared, Expr init, boolean mutable, int line)
            implements Stmt {
    }

    /** {@code target op value;} where op is one of {@code = += -= *= /=}. */
    public record Assign(Expr target, String op, Expr value, int line) implements Stmt {
    }

    /** {@code elseBranch} is a Block, another If (else-if chain), or null. */
    public record If(Expr cond, Block then, Stmt elseBranch, int line) implements Stmt {
    }

    public record While(Expr cond, Block body, int line) implements Stmt {
    }

    /** {@code for i in from..to { }} — half-open integer range. */
    public record ForRange(String var, Expr from, Expr to, Block body, int line) implements Stmt {
    }

    public record Return(Expr value, int line) implements Stmt {
    }

    public record Break(int line) implements Stmt {
    }

    public record Continue(int line) implements Stmt {
    }

    public record ExprStmt(Expr expr, int line) implements Stmt {
    }

    // ─── Expressions ──────────────────────────────────────────────────────

    public sealed interface Expr permits Bin, Un, IntLit, FloatLit, BoolLit, SizeLit,
            Ident, Member, Index, Call {
        int line();
    }

    public record Bin(String op, Expr left, Expr right, int line) implements Expr {
    }

    public record Un(String op, Expr operand, int line) implements Expr {
    }

    public record IntLit(long value, String text, int line) implements Expr {
    }

    public record FloatLit(double value, String text, int line) implements Expr {
    }

    public record BoolLit(boolean value, int line) implements Expr {
    }

    /** A size literal like {@code 4 GiB}, always in bytes; type i64, host-only. */
    public record SizeLit(long bytes, String text, int line) implements Expr {
    }

    public record Ident(String name, int line) implements Expr {
    }

    public record Member(Expr target, String name, int line) implements Expr {
    }

    public record Index(Expr target, Expr index, int line) implements Expr {
    }

    /** Callee is an Ident (free fn / builtin / constructor) or Member (method). */
    public record Call(Expr callee, List<Expr> args, int line) implements Expr {
    }

    // ─── Plan declarations ────────────────────────────────────────────────

    public record PlanDecl(String name, DeviceDecl device, List<PoolDecl> pools,
                           List<WhenDecl> whens, List<PressureRule> rules, int line) {
    }

    /**
     * {@code budget} is a const expression over sizes, pins, and the host
     * environment (e.g. {@code (vram otherwise FLOOR) * 3 / 4}), or null when
     * unset; {@code headroom} -1 = unset (fraction 0..1 otherwise).
     */
    public record DeviceDecl(Expr budget, double headroom, int line) {
    }

    /**
     * A pool draft. {@code parent} names the pool it inherits from (single
     * inheritance). Sentinels mark unset fields so overlays can merge.
     */
    public record PoolDecl(String name, String parent, String category,
                           long budgetBytes, double budgetShare, int priority,
                           String storage, String grow, ArenaDecl arena, int line) {
        public static final int PRIORITY_UNSET = Integer.MIN_VALUE;
    }

    /**
     * All -1 sentinels for unset; {@code reserve}, {@code growth}, and
     * {@code trim} are fractions/factors ({@code trim} = shrink the arena when
     * live bytes fall under this share of capacity; 0 disables).
     */
    public record ArenaDecl(long vertexBytes, long indexBytes, double growth, double reserve,
                            int align, double trim, int line) {
    }

    /** Pools inside apply only when {@code condition} const-evaluates to true. */
    public record WhenDecl(Expr condition, List<PoolDecl> pools, int line) {
    }

    /** {@code on pressure > threshold { shed a, b; }} — threshold is a fraction 0..1. */
    public record PressureRule(double threshold, List<String> shed, int line) {
    }
}
