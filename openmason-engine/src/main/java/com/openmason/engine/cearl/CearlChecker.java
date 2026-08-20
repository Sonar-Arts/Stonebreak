package com.openmason.engine.cearl;

import com.openmason.engine.cearl.CearlAst.Assign;
import com.openmason.engine.cearl.CearlAst.Bin;
import com.openmason.engine.cearl.CearlAst.Block;
import com.openmason.engine.cearl.CearlAst.BoolLit;
import com.openmason.engine.cearl.CearlAst.Call;
import com.openmason.engine.cearl.CearlAst.ConstDecl;
import com.openmason.engine.cearl.CearlAst.Dir;
import com.openmason.engine.cearl.CearlAst.Expr;
import com.openmason.engine.cearl.CearlAst.ExprStmt;
import com.openmason.engine.cearl.CearlAst.Field;
import com.openmason.engine.cearl.CearlAst.FloatLit;
import com.openmason.engine.cearl.CearlAst.FnDecl;
import com.openmason.engine.cearl.CearlAst.ForRange;
import com.openmason.engine.cearl.CearlAst.Ident;
import com.openmason.engine.cearl.CearlAst.If;
import com.openmason.engine.cearl.CearlAst.Index;
import com.openmason.engine.cearl.CearlAst.IntLit;
import com.openmason.engine.cearl.CearlAst.KernelDecl;
import com.openmason.engine.cearl.CearlAst.Let;
import com.openmason.engine.cearl.CearlAst.Member;
import com.openmason.engine.cearl.CearlAst.Param;
import com.openmason.engine.cearl.CearlAst.Program;
import com.openmason.engine.cearl.CearlAst.Return;
import com.openmason.engine.cearl.CearlAst.SizeLit;
import com.openmason.engine.cearl.CearlAst.Stmt;
import com.openmason.engine.cearl.CearlAst.StructDecl;
import com.openmason.engine.cearl.CearlAst.TypeRef;
import com.openmason.engine.cearl.CearlAst.Un;
import com.openmason.engine.cearl.CearlAst.While;
import com.openmason.engine.cearl.CearlType.PrimKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CEARL's static type checker. Resolves every expression to a
 * {@link CearlType}, enforces the device-code rules the GLSL target needs
 * (no recursion, no {@code i64}, buffers only indexed, read/write direction
 * discipline), and computes the facts the emitter consumes: expression types,
 * the struct registry, function signatures, evaluated consts, and which
 * methods mutate {@code self}.
 *
 * <p>Design choices worth naming:
 * <ul>
 *   <li><b>Strict types, friendly literals.</b> There are no implicit
 *       conversions between variables, but an integer literal adapts to the
 *       type it meets ({@code x * 2} works for f32/u32/i64 {@code x}) — the
 *       checker rewrites the literal's recorded type so the emitter prints
 *       {@code 2.0} / {@code 2u} correctly.
 *   <li><b>Mutability is checked, not trusted.</b> {@code let} locals,
 *       function parameters, and {@code in} buffers are immutable; methods
 *       that mutate {@code self} (directly or transitively, computed by a
 *       syntactic fixpoint) require a mutable receiver.
 *   <li><b>Recursion is a compile error</b> — the GPU target has no stack.
 * </ul>
 */
public final class CearlChecker {

    /** GLSL words and emitter-reserved names user identifiers must avoid. */
    private static final Set<String> GPU_RESERVED = Set.of(
        "int", "float", "uint", "double", "bool", "void", "true", "false",
        "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
        "bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4",
        "in", "out", "inout", "uniform", "buffer", "shared", "const", "flat",
        "return", "if", "else", "for", "while", "do", "switch", "case", "default",
        "break", "continue", "discard", "main", "layout", "struct", "precision",
        "sample", "filter", "union", "common", "partition", "active",
        "min", "max", "clamp", "abs", "floor", "ceil", "sqrt", "pow", "exp", "log",
        "sin", "cos", "mod", "mix", "dot", "cross", "normalize", "length", "barrier",
        "atomicAdd", "atomicMin", "atomicMax", "count", "pick",
        "self", "gid", "lid", "wgid", "nwg");

    private static final Set<String> BUILTIN_VARS = Set.of("gid", "lid", "wgid", "nwg");

    public record StructInfo(StructDecl decl, LinkedHashMap<String, CearlType> fields,
                             LinkedHashMap<String, FnInfo> methods) {
    }

    public record FnInfo(FnDecl decl, List<CearlType> paramTypes, CearlType ret,
                         boolean mutatesSelf, String key) {
    }

    public record ConstInfo(CearlType type, Object value, int line) {
    }

    public record Checked(Program program,
                          Map<Expr, CearlType> exprTypes,
                          LinkedHashMap<String, StructInfo> structs,
                          LinkedHashMap<String, FnInfo> fns,
                          LinkedHashMap<String, ConstInfo> consts,
                          Set<Expr> implicitSelf,
                          Map<Object, Long> resolvedSizes) {

        public CearlType typeOf(Expr e) {
            return exprTypes.get(e);
        }

        /** True when this identifier is an implicit field access inside a method. */
        public boolean isImplicitSelf(Expr e) {
            return implicitSelf.contains(e);
        }
    }

    // ─── State ────────────────────────────────────────────────────────────

    private final String sourceName;
    private final Map<Expr, CearlType> exprTypes = new IdentityHashMap<>();
    private final LinkedHashMap<String, StructInfo> structs = new LinkedHashMap<>();
    private final LinkedHashMap<String, FnInfo> fns = new LinkedHashMap<>();
    private final LinkedHashMap<String, ConstInfo> consts = new LinkedHashMap<>();
    private final Map<String, Boolean> selfMutation = new HashMap<>();
    private final Map<String, Set<String>> callGraph = new HashMap<>();
    private final Set<Expr> implicitSelf =
        java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** Evaluated sizes of uniform-array takes and shared decls, keyed by node identity. */
    private final Map<Object, Long> resolvedSizes = new IdentityHashMap<>();
    private Map<String, Object> constValues = new LinkedHashMap<>();
    private Map<String, Long> hostEnv = Map.of();

    /** What kind of storage a scope entry names — array rules differ per kind. */
    private enum Storage { VALUE, BUFFER, UNIFORM_ARRAY, SHARED }

    /** One lexical scope entry. {@code bufferDir} is non-null only for BUFFER. */
    private record Local(CearlType type, boolean mutable, Storage storage, Dir bufferDir) {

        static Local value(CearlType type, boolean mutable) {
            return new Local(type, mutable, Storage.VALUE, null);
        }
    }

    private final Deque<Map<String, Local>> scopes = new ArrayDeque<>();
    private CearlType currentReturn = CearlType.VOID;
    private String currentFnKey;
    /** Field table of the form whose method is being checked; null elsewhere. */
    private LinkedHashMap<String, CearlType> currentOwnerFields;
    private String currentOwnerName;
    private int loopDepth;

    private CearlChecker(String sourceName) {
        this.sourceName = sourceName;
    }

    public static Checked check(Program program, String sourceName, Map<String, Long> env) {
        CearlChecker checker = new CearlChecker(sourceName);
        checker.run(program, env);
        return new Checked(program, checker.exprTypes, checker.structs, checker.fns,
            checker.consts, checker.implicitSelf, checker.resolvedSizes);
    }

    private void run(Program program, Map<String, Long> env) {
        registerStructs(program.structs());
        registerConsts(program.consts(), env);
        registerFns(program);
        computeSelfMutation(program.structs());
        checkBodies(program);
        detectRecursion();
    }

    // ─── Registration passes ──────────────────────────────────────────────

    private void registerStructs(List<StructDecl> decls) {
        for (StructDecl s : decls) {
            checkName(s.name(), "form", s.line());
            if (CearlType.builtin(s.name()) != null) {
                throw err(s.line(), "'" + s.name() + "' is a builtin type name");
            }
            if (structs.containsKey(s.name())) {
                throw err(s.line(), "duplicate form '" + s.name() + "'");
            }
            structs.put(s.name(), new StructInfo(s, new LinkedHashMap<>(), new LinkedHashMap<>()));
        }
        // Resolve field types now that every struct name is known.
        for (StructDecl s : decls) {
            StructInfo info = structs.get(s.name());
            for (Field f : s.fields()) {
                checkName(f.name(), "field", f.line());
                if (info.fields().containsKey(f.name())) {
                    throw err(f.line(), "duplicate field '" + f.name()
                        + "' in form '" + s.name() + "'");
                }
                CearlType t = resolveType(f.type());
                if (t.is(PrimKind.I64)) {
                    throw err(f.line(), "i64 cannot appear in a form"
                        + " — forms are GPU-layout types and GLSL 430 has no 64-bit integers");
                }
                if (t.is(PrimKind.BOOL)) {
                    throw err(f.line(), "bool fields have no defined GPU layout"
                        + " — use u32 (0/1) instead");
                }
                info.fields().put(f.name(), t);
            }
        }
        detectStructCycles();
    }

    private void detectStructCycles() {
        Set<String> done = new HashSet<>();
        for (String name : structs.keySet()) {
            structCycleDfs(name, new ArrayList<>(), done);
        }
    }

    private void structCycleDfs(String name, List<String> path, Set<String> done) {
        if (done.contains(name)) {
            return;
        }
        int cycleStart = path.indexOf(name);
        if (cycleStart >= 0) {
            throw err(structs.get(name).decl().line(), "form cycle: "
                + String.join(" -> ", path.subList(cycleStart, path.size())) + " -> " + name);
        }
        path.add(name);
        for (CearlType t : structs.get(name).fields().values()) {
            if (t instanceof CearlType.Struct s) {
                structCycleDfs(s.name(), path, done);
            }
        }
        path.removeLast();
        done.add(name);
    }

    private void registerConsts(List<ConstDecl> decls, Map<String, Long> env) {
        this.hostEnv = env;
        Map<String, Object> values = constValues;
        CearlConstEval eval = new CearlConstEval(sourceName, values, env);
        for (ConstDecl c : decls) {
            checkName(c.name(), "const", c.line());
            if (consts.containsKey(c.name())) {
                throw err(c.line(), "duplicate const '" + c.name() + "'");
            }
            Object value = eval.eval(c.value());
            CearlType type = constType(c, value);
            consts.put(c.name(), new ConstInfo(type, value, c.line()));
            values.put(c.name(), value);
        }
    }

    private CearlType constType(ConstDecl c, Object value) {
        CearlType inferred;
        if (value instanceof Boolean) {
            inferred = CearlType.BOOL;
        } else if (value instanceof Double) {
            inferred = CearlType.F32;
        } else {
            long v = (Long) value;
            boolean sizeShaped = c.value() instanceof SizeLit
                || v > Integer.MAX_VALUE || v < Integer.MIN_VALUE;
            inferred = sizeShaped ? CearlType.I64 : CearlType.I32;
        }
        if (c.declared() == null) {
            return inferred;
        }
        CearlType declared = resolveType(c.declared());
        boolean ok = switch (declared) {
            case CearlType.Prim p -> switch (p.kind()) {
                case BOOL -> value instanceof Boolean;
                case F32 -> value instanceof Double || value instanceof Long;
                case I32 -> value instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE;
                case U32 -> value instanceof Long l && l >= 0 && l <= 0xFFFFFFFFL;
                case I64 -> value instanceof Long;
                case VOID -> false;
            };
            default -> false;
        };
        if (!ok) {
            throw err(c.line(), "const '" + c.name() + "' is declared " + declared.display()
                + " but its value doesn't fit that type");
        }
        return declared;
    }

    private void registerFns(Program program) {
        for (FnDecl f : program.fns()) {
            registerFn(f, null);
        }
        for (StructDecl s : program.structs()) {
            for (FnDecl m : s.methods()) {
                registerFn(m, s.name());
            }
        }
        Set<String> kernelNames = new HashSet<>();
        for (KernelDecl k : program.kernels()) {
            checkName(k.name(), "kernel", k.line());
            if (!kernelNames.add(k.name())) {
                throw err(k.line(), "duplicate kernel '" + k.name() + "'");
            }
        }
    }

    private void registerFn(FnDecl f, String owner) {
        checkName(f.name(), owner == null ? "function" : "method", f.line());
        String key = fnKey(owner, f.name());
        LinkedHashMap<String, FnInfo> registry =
            owner == null ? fns : structs.get(owner).methods();
        if (registry.containsKey(f.name())) {
            throw err(f.line(), "duplicate " + (owner == null ? "craft" : "method")
                + " '" + f.name() + "'");
        }
        if (owner == null && CearlType.builtin(f.name()) != null) {
            throw err(f.line(), "'" + f.name() + "' is a type name — pick another craft name");
        }
        List<CearlType> paramTypes = new ArrayList<>();
        Set<String> paramNames = new HashSet<>();
        for (Param p : f.params()) {
            checkName(p.name(), "parameter", p.line());
            if (!paramNames.add(p.name())) {
                throw err(p.line(), "duplicate parameter '" + p.name() + "'");
            }
            if (p.type().array()) {
                throw err(p.line(), "crafts cannot take array parameters"
                    + " — runtime-sized buffers exist only as kernel takes; pass an element instead");
            }
            CearlType t = resolveType(p.type());
            requireDeviceType(t, p.line(), "parameter '" + p.name() + "'");
            paramTypes.add(t);
        }
        CearlType ret = f.ret() == null ? CearlType.VOID : resolveType(f.ret());
        if (!ret.is(PrimKind.VOID)) {
            requireDeviceType(ret, f.line(), "return type");
        }
        registry.put(f.name(), new FnInfo(f, paramTypes, ret, false, key));
    }

    /**
     * Syntactic fixpoint: a method mutates self when it assigns into
     * {@code self.*} or a bare field of its own form, or calls another method
     * on {@code self}/a field that mutates self. Runs before body checking so
     * receiver-mutability rules can consult it in any declaration order.
     * (Bare-field roots are sound because the checker forbids locals and
     * parameters from shadowing field names.)
     */
    private void computeSelfMutation(List<StructDecl> decls) {
        Map<String, FnDecl> methodsByKey = new HashMap<>();
        Map<String, Set<String>> fieldsByOwner = new HashMap<>();
        for (StructDecl s : decls) {
            fieldsByOwner.put(s.name(), structs.get(s.name()).fields().keySet());
            for (FnDecl m : s.methods()) {
                String key = fnKey(s.name(), m.name());
                methodsByKey.put(key, m);
                selfMutation.put(key,
                    directlyMutatesSelf(m.body(), fieldsByOwner.get(s.name())));
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, FnDecl> e : methodsByKey.entrySet()) {
                if (selfMutation.get(e.getKey())) {
                    continue;
                }
                if (callsMutatingMethodOnSelf(e.getValue().body(), e.getValue().owner(),
                        fieldsByOwner.get(e.getValue().owner()))) {
                    selfMutation.put(e.getKey(), true);
                    changed = true;
                }
            }
        }
        // Publish into the registered FnInfos.
        for (StructDecl s : decls) {
            StructInfo info = structs.get(s.name());
            for (Map.Entry<String, FnInfo> e : info.methods().entrySet()) {
                FnInfo old = e.getValue();
                boolean mutates = selfMutation.getOrDefault(old.key(), false);
                e.setValue(new FnInfo(old.decl(), old.paramTypes(), old.ret(), mutates, old.key()));
            }
        }
    }

    private boolean directlyMutatesSelf(Stmt stmt, Set<String> fields) {
        return switch (stmt) {
            case Block b -> b.stmts().stream().anyMatch(s -> directlyMutatesSelf(s, fields));
            case Assign a -> rootTouchesSelf(a.target(), fields);
            case If i -> directlyMutatesSelf(i.then(), fields)
                || (i.elseBranch() != null && directlyMutatesSelf(i.elseBranch(), fields));
            case While w -> directlyMutatesSelf(w.body(), fields);
            case ForRange f -> directlyMutatesSelf(f.body(), fields);
            default -> false;
        };
    }

    private boolean callsMutatingMethodOnSelf(Stmt stmt, String owner, Set<String> fields) {
        List<Call> calls = new ArrayList<>();
        collectCalls(stmt, calls);
        for (Call c : calls) {
            if (c.callee() instanceof Member m && rootTouchesSelf(m.target(), fields)) {
                // Method on self resolves in the owner; a method on a struct-typed
                // field could belong to another form — conservatively treat any
                // known-mutating method name reachable through self as mutation.
                for (StructInfo info : structs.values()) {
                    Boolean mutates = selfMutation.get(fnKey(info.decl().name(), m.name()));
                    if (mutates != null && mutates && info.methods().containsKey(m.name())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void collectCalls(Stmt stmt, List<Call> out) {
        switch (stmt) {
            case Block b -> b.stmts().forEach(s -> collectCalls(s, out));
            case If i -> {
                collectCallsExpr(i.cond(), out);
                collectCalls(i.then(), out);
                if (i.elseBranch() != null) {
                    collectCalls(i.elseBranch(), out);
                }
            }
            case While w -> {
                collectCallsExpr(w.cond(), out);
                collectCalls(w.body(), out);
            }
            case ForRange f -> {
                collectCallsExpr(f.from(), out);
                collectCallsExpr(f.to(), out);
                collectCalls(f.body(), out);
            }
            case Assign a -> {
                collectCallsExpr(a.target(), out);
                collectCallsExpr(a.value(), out);
            }
            case Let l -> collectCallsExpr(l.init(), out);
            case Return r -> {
                if (r.value() != null) {
                    collectCallsExpr(r.value(), out);
                }
            }
            case ExprStmt e -> collectCallsExpr(e.expr(), out);
            default -> { }
        }
    }

    private void collectCallsExpr(Expr expr, List<Call> out) {
        switch (expr) {
            case Call c -> {
                out.add(c);
                if (c.callee() instanceof Member m) {
                    collectCallsExpr(m.target(), out);
                }
                c.args().forEach(a -> collectCallsExpr(a, out));
            }
            case Bin b -> {
                collectCallsExpr(b.left(), out);
                collectCallsExpr(b.right(), out);
            }
            case Un u -> collectCallsExpr(u.operand(), out);
            case Member m -> collectCallsExpr(m.target(), out);
            case Index i -> {
                collectCallsExpr(i.target(), out);
                collectCallsExpr(i.index(), out);
            }
            default -> { }
        }
    }

    /** Root of the lvalue chain is {@code self} or a bare field of the owner. */
    private static boolean rootTouchesSelf(Expr e, Set<String> fields) {
        return switch (e) {
            case Ident i -> i.name().equals("self") || fields.contains(i.name());
            case Member m -> rootTouchesSelf(m.target(), fields);
            case Index i -> rootTouchesSelf(i.target(), fields);
            default -> false;
        };
    }

    // ─── Body checking ────────────────────────────────────────────────────

    private void checkBodies(Program program) {
        for (StructDecl s : program.structs()) {
            for (FnDecl m : s.methods()) {
                checkFnBody(structs.get(s.name()).methods().get(m.name()));
            }
        }
        for (FnDecl f : program.fns()) {
            checkFnBody(fns.get(f.name()));
        }
        for (KernelDecl k : program.kernels()) {
            checkKernel(k);
        }
    }

    private void checkFnBody(FnInfo info) {
        FnDecl f = info.decl();
        currentFnKey = info.key();
        currentReturn = info.ret();
        pushScope();
        if (f.owner() != null) {
            currentOwnerName = f.owner();
            currentOwnerFields = structs.get(f.owner()).fields();
            scopes.peek().put("self",
                Local.value(new CearlType.Struct(f.owner()), true));
        }
        for (int i = 0; i < f.params().size(); i++) {
            declare(f.params().get(i).name(),
                Local.value(info.paramTypes().get(i), false), f.params().get(i).line());
        }
        checkBlock(f.body());
        popScope();
        if (!info.ret().is(PrimKind.VOID) && !terminates(f.body())) {
            throw err(f.line(), (f.owner() != null ? "method" : "craft") + " '" + f.name()
                + "' declares a result type but not every path ends in a give");
        }
        currentFnKey = null;
        currentOwnerName = null;
        currentOwnerFields = null;
    }

    private void checkKernel(KernelDecl k) {
        currentFnKey = "kernel " + k.name();
        currentReturn = CearlType.VOID;
        pushScope();
        Set<String> names = new HashSet<>();
        for (Param p : k.params()) {
            checkName(p.name(), "kernel parameter", p.line());
            if (!names.add(p.name())) {
                throw err(p.line(), "duplicate kernel parameter '" + p.name() + "'");
            }
            CearlType t = resolveType(p.type());
            if (t instanceof CearlType.Array array) {
                CearlType elem = array.elem();
                requireDeviceType(elem, p.line(), "array element type");
                if (elem.is(PrimKind.BOOL)) {
                    throw err(p.line(), "bool arrays have no defined GPU layout — use u32");
                }
                if (p.type().size() != null) {
                    // Sized array take = a uniform array (register space):
                    // 'take planes: vec4[6]' — read-only, indexable, tiny.
                    if (p.dir() != Dir.IN) {
                        throw err(p.line(), "uniform arrays are inputs — a sized take"
                            + " cannot be 'out' or 'inout'; use an unsized buffer");
                    }
                    if (elem instanceof CearlType.Struct) {
                        throw err(p.line(), "uniform arrays hold scalars and vectors"
                            + " — pass form data through a buffer take");
                    }
                    long size = arraySize(p.type().size(), p.line(), 1, 1024,
                        "uniform arrays live in register space — pass big data"
                            + " through a buffer take");
                    resolvedSizes.put(p, size);
                    declare(p.name(),
                        new Local(array, false, Storage.UNIFORM_ARRAY, null), p.line());
                } else {
                    declare(p.name(),
                        new Local(array, p.dir() != Dir.IN, Storage.BUFFER, p.dir()),
                        p.line());
                }
            } else {
                if (p.dir() != Dir.IN) {
                    throw err(p.line(), "'" + p.name() + "' is a uniform (non-array) take"
                        + " — only buffer takes can be 'out' or 'inout'");
                }
                if (t instanceof CearlType.Struct) {
                    throw err(p.line(), "form uniforms are not supported"
                        + " — pass structured data through a buffer take");
                }
                requireDeviceType(t, p.line(), "uniform '" + p.name() + "'");
                declare(p.name(), Local.value(t, false), p.line());
            }
        }
        for (CearlAst.SharedDecl sh : k.shareds()) {
            checkName(sh.name(), "shared array", sh.line());
            CearlType t = resolveType(sh.type());
            CearlType elem = ((CearlType.Array) t).elem();
            requireDeviceType(elem, sh.line(), "shared element type");
            if (elem.is(PrimKind.BOOL) || elem instanceof CearlType.Struct) {
                throw err(sh.line(), "shared memory holds scalars and vectors — use u32"
                    + " for flags and split forms into component arrays");
            }
            long size = arraySize(sh.type().size(), sh.line(), 1, 16384,
                "that many elements cannot fit the portable 32 KiB shared budget");
            long bytes = size * 4L * (elem instanceof CearlType.Vec v ? v.size() : 1);
            if (bytes > 32768) {
                throw err(sh.line(), "shared array '" + sh.name() + "' needs " + bytes
                    + " bytes — the portable per-workgroup budget is 32 KiB");
            }
            resolvedSizes.put(sh, size);
            declare(sh.name(), new Local(t, true, Storage.SHARED, null), sh.line());
        }
        checkBlock(k.body());
        popScope();
        currentFnKey = null;
    }

    /** Const-evaluates a sized-array length and range-checks it. */
    private long arraySize(Expr sizeExpr, int line, long min, long max, String hint) {
        Object v = new CearlConstEval(sourceName, constValues, hostEnv).eval(sizeExpr);
        if (!(v instanceof Long size)) {
            throw err(line, "array sizes must be whole numbers");
        }
        if (size < min || size > max) {
            throw err(line, "array size " + size + " is out of range [" + min + ", " + max
                + "] — " + hint);
        }
        return size;
    }

    private void checkBlock(Block block) {
        pushScope();
        for (Stmt s : block.stmts()) {
            checkStmt(s);
        }
        popScope();
    }

    private void checkStmt(Stmt stmt) {
        switch (stmt) {
            case Block b -> checkBlock(b);
            case Let l -> {
                CearlType init = checkExpr(l.init());
                if (init.is(PrimKind.VOID)) {
                    throw err(l.line(), "cannot store a void value in '" + l.name() + "'");
                }
                if (init instanceof CearlType.Array) {
                    throw err(l.line(), "buffers cannot be stored in locals"
                        + " — index them directly");
                }
                CearlType type = init;
                if (l.declared() != null) {
                    type = resolveType(l.declared());
                    coerce(l.init(), init, type, l.line(),
                        "initializer of '" + l.name() + "'");
                }
                requireDeviceType(type, l.line(), "'" + l.name() + "'");
                checkName(l.name(), "variable", l.line());
                declare(l.name(), Local.value(type, l.mutable()), l.line());
            }
            case Assign a -> {
                CearlType target = checkLvalue(a.target(), a.op().equals("=") ? Mode.WRITE : Mode.READ_WRITE);
                CearlType value = checkExpr(a.value());
                if (a.op().equals("=")) {
                    coerce(a.value(), value, target, a.line(), "assignment");
                } else {
                    String binOp = a.op().substring(0, 1);
                    CearlType result = arithType(binOp, target, value, a.target(), a.value(), a.line());
                    if (!result.equals(target)) {
                        throw err(a.line(), "'" + a.op() + "' would change the type of the target ("
                            + target.display() + " vs " + result.display() + ")");
                    }
                }
            }
            case If i -> {
                requireBool(i.cond(), checkExpr(i.cond()), "if condition");
                checkBlock(i.then());
                if (i.elseBranch() != null) {
                    checkStmt(i.elseBranch());
                }
            }
            case While w -> {
                requireBool(w.cond(), checkExpr(w.cond()), "while condition");
                loopDepth++;
                checkBlock(w.body());
                loopDepth--;
            }
            case ForRange f -> {
                CearlType from = checkExpr(f.from());
                CearlType to = checkExpr(f.to());
                CearlType loopType = unifyRange(f, from, to);
                pushScope();
                checkName(f.var(), "loop variable", f.line());
                declare(f.var(), Local.value(loopType, false), f.line());
                loopDepth++;
                checkBlock(f.body());
                loopDepth--;
                popScope();
            }
            case Return r -> {
                if (r.value() == null) {
                    if (!currentReturn.is(PrimKind.VOID)) {
                        throw err(r.line(), "this craft gives " + currentReturn.display()
                            + " — bare 'give' needs a value");
                    }
                } else {
                    if (currentReturn.is(PrimKind.VOID)) {
                        throw err(r.line(), "void crafts and kernels cannot give a value");
                    }
                    CearlType t = checkExpr(r.value());
                    coerce(r.value(), t, currentReturn, r.line(), "return value");
                }
            }
            case CearlAst.Break b -> {
                if (loopDepth == 0) {
                    throw err(b.line(), "'break' outside a loop");
                }
            }
            case CearlAst.Continue c -> {
                if (loopDepth == 0) {
                    throw err(c.line(), "'continue' outside a loop");
                }
            }
            case ExprStmt e -> checkExpr(e.expr());
        }
    }

    private CearlType unifyRange(ForRange f, CearlType from, CearlType to) {
        boolean fromLit = f.from() instanceof IntLit;
        boolean toLit = f.to() instanceof IntLit;
        CearlType want;
        if (from.is(PrimKind.U32) || to.is(PrimKind.U32)) {
            want = CearlType.U32;
        } else {
            want = CearlType.I32;
        }
        if (!from.equals(want)) {
            if (!fromLit) {
                throw err(f.line(), "range bounds disagree: " + from.display()
                    + " vs " + to.display());
            }
            exprTypes.put(f.from(), want);
        }
        if (!to.equals(want)) {
            if (!toLit) {
                throw err(f.line(), "range bounds disagree: " + from.display()
                    + " vs " + to.display());
            }
            exprTypes.put(f.to(), want);
        }
        if (!want.is(PrimKind.I32) && !want.is(PrimKind.U32)) {
            throw err(f.line(), "range bounds must be i32 or u32 (got " + want.display() + ")");
        }
        return want;
    }

    /** Simple structural check: does every path through this block return? */
    private boolean terminates(Stmt stmt) {
        return switch (stmt) {
            case Return r -> true;
            case Block b -> !b.stmts().isEmpty() && terminates(b.stmts().getLast());
            case If i -> i.elseBranch() != null && terminates(i.then()) && terminates(i.elseBranch());
            default -> false;
        };
    }

    // ─── Expression checking ──────────────────────────────────────────────

    private enum Mode { READ, WRITE, READ_WRITE }

    private CearlType checkExpr(Expr expr) {
        CearlType t = computeType(expr);
        exprTypes.put(expr, t);
        return t;
    }

    private CearlType computeType(Expr expr) {
        return switch (expr) {
            case IntLit i -> CearlType.I32;
            case FloatLit f -> CearlType.F32;
            case BoolLit b -> CearlType.BOOL;
            case SizeLit s -> throw err(s.line(), "size literals (" + s.text()
                + ") are host-only — they belong in consts and plans, not device code");
            case Ident id -> identType(id, Mode.READ);
            case Member m -> memberType(m, Mode.READ);
            case Index ix -> indexType(ix, Mode.READ);
            case Un u -> unaryType(u);
            case Bin b -> binType(b);
            case Call c -> callType(c);
        };
    }

    private CearlType identType(Ident id, Mode mode) {
        Local local = lookup(id.name());
        if (local != null) {
            if (local.type() instanceof CearlType.Array) {
                if (mode != Mode.READ) {
                    throw err(id.line(), "cannot assign a whole buffer — assign its elements");
                }
                // Bare buffer identifiers are only legal inside count()/index —
                // callers that allow it check before calling.
                return local.type();
            }
            if (mode != Mode.READ && !local.mutable()) {
                throw err(id.line(), "'" + id.name() + "' is immutable — declare it 'flux'"
                    + " (fix locals and craft parameters are read-only)");
            }
            return local.type();
        }
        // Inside a method, a form's fields are directly visible (implicit self).
        if (currentOwnerFields != null) {
            CearlType field = currentOwnerFields.get(id.name());
            if (field != null) {
                implicitSelf.add(id);
                return field; // self is mutable, so writes are fine in any mode.
            }
        }
        if (BUILTIN_VARS.contains(id.name())) {
            if (mode != Mode.READ) {
                throw err(id.line(), "'" + id.name() + "' is a builtin and cannot be assigned");
            }
            return CearlType.vec(PrimKind.U32, 3);
        }
        ConstInfo c = consts.get(id.name());
        if (c != null) {
            if (mode != Mode.READ) {
                throw err(id.line(), "'" + id.name() + "' is a const and cannot be assigned");
            }
            if (c.type().is(PrimKind.I64)) {
                throw err(id.line(), "const '" + id.name() + "' is i64 (host-only)"
                    + " and cannot be used in device code");
            }
            return c.type();
        }
        throw err(id.line(), "unknown name '" + id.name() + "'");
    }

    private CearlType memberType(Member m, Mode mode) {
        CearlType target = checkExprIn(m.target(), mode);
        if (target instanceof CearlType.Struct s) {
            CearlType field = structs.get(s.name()).fields().get(m.name());
            if (field == null) {
                StructInfo info = structs.get(s.name());
                if (info.methods().containsKey(m.name())) {
                    throw err(m.line(), "'" + m.name() + "' is a method of " + s.name()
                        + " — call it: ." + m.name() + "(...)");
                }
                throw err(m.line(), "form " + s.name() + " has no field '" + m.name()
                    + "' — fields: " + info.fields().keySet());
            }
            return field;
        }
        if (target instanceof CearlType.Vec v) {
            return swizzleType(m, v, mode);
        }
        throw err(m.line(), "'." + m.name() + "' needs a form value or vector on the left"
            + " (got " + target.display() + ")");
    }

    private CearlType swizzleType(Member m, CearlType.Vec v, Mode mode) {
        String s = m.name();
        if (s.length() < 1 || s.length() > 4) {
            throw err(m.line(), "swizzle '." + s + "' must pick 1-4 components");
        }
        for (char c : s.toCharArray()) {
            int comp = "xyzw".indexOf(c);
            if (comp < 0 || comp >= v.size()) {
                throw err(m.line(), "component '" + c + "' does not exist on " + v.display());
            }
        }
        if (mode != Mode.READ && s.length() > 1) {
            throw err(m.line(), "assign one component at a time (." + s.charAt(0) + " = ...)");
        }
        return s.length() == 1 ? new CearlType.Prim(v.elem()) : CearlType.vec(v.elem(), s.length());
    }

    private CearlType indexType(Index ix, Mode mode) {
        CearlType target;
        if (ix.target() instanceof Ident id) {
            Local local = lookup(id.name());
            if (local != null && local.type() instanceof CearlType.Array arr) {
                switch (local.storage()) {
                    case BUFFER -> {
                        if (mode == Mode.READ && local.bufferDir() == Dir.OUT) {
                            throw err(ix.line(), "buffer '" + id.name()
                                + "' is write-only ('out')"
                                + " — mark the take 'inout' to read it back");
                        }
                        if (mode != Mode.READ && local.bufferDir() == Dir.IN) {
                            throw err(ix.line(), "buffer '" + id.name()
                                + "' is read-only ('in')"
                                + " — mark the take 'out' or 'inout' to write it");
                        }
                    }
                    case UNIFORM_ARRAY -> {
                        if (mode != Mode.READ) {
                            throw err(ix.line(), "uniform array '" + id.name()
                                + "' is read-only — writable data goes through a buffer");
                        }
                    }
                    case SHARED, VALUE -> { }
                }
                exprTypes.put(ix.target(), local.type());
                CearlType idx = checkExpr(ix.index());
                requireIndexType(ix.index(), idx);
                return arr.elem();
            }
        }
        throw err(ix.line(), "only arrays can be indexed — '[i]' needs a kernel take"
            + " buffer, a uniform array, or a shared array on the left");
    }

    private void requireIndexType(Expr idxExpr, CearlType idx) {
        if (idx.is(PrimKind.I32) || idx.is(PrimKind.U32)) {
            return;
        }
        if (idxExpr instanceof IntLit) {
            exprTypes.put(idxExpr, CearlType.U32);
            return;
        }
        throw err(idxExpr.line(), "buffer indices must be i32 or u32 (got " + idx.display() + ")");
    }

    private CearlType checkExprIn(Expr expr, Mode mode) {
        CearlType t = switch (expr) {
            case Ident id -> identType(id, mode);
            case Member m -> memberType(m, mode);
            case Index ix -> indexType(ix, mode);
            default -> computeType(expr);
        };
        exprTypes.put(expr, t);
        return t;
    }

    private CearlType checkLvalue(Expr target, Mode mode) {
        return checkExprIn(target, mode == Mode.READ ? Mode.READ_WRITE : mode);
    }

    private CearlType unaryType(Un u) {
        CearlType t = checkExpr(u.operand());
        if (u.op().equals("!")) {
            if (!t.is(PrimKind.BOOL)) {
                throw err(u.line(), "'not' needs a bool (got " + t.display() + ")");
            }
            return t;
        }
        if (t.isNumeric() || t instanceof CearlType.Vec) {
            if (t.is(PrimKind.U32) || (t instanceof CearlType.Vec v && v.elem() == PrimKind.U32)) {
                throw err(u.line(), "cannot negate an unsigned value — cast to i32 first");
            }
            return t;
        }
        throw err(u.line(), "unary '-' needs a number or vector (got " + t.display() + ")");
    }

    private CearlType binType(Bin b) {
        String op = b.op();
        if (op.equals("otherwise")) {
            throw err(b.line(), "'otherwise' is host-only — it picks a fallback"
                + " in plans and pins, not in device code");
        }
        CearlType lt = checkExpr(b.left());
        CearlType rt = checkExpr(b.right());

        if (op.equals("&&") || op.equals("||")) {
            requireBool(b.left(), lt, "'" + op + "' operand");
            requireBool(b.right(), rt, "'" + op + "' operand");
            return CearlType.BOOL;
        }
        if (op.equals("==") || op.equals("!=")) {
            if (lt.is(PrimKind.BOOL) && rt.is(PrimKind.BOOL)) {
                return CearlType.BOOL;
            }
            matchNumeric(b, lt, rt);
            return CearlType.BOOL;
        }
        if (op.equals("<") || op.equals("<=") || op.equals(">") || op.equals(">=")) {
            matchNumeric(b, lt, rt);
            return CearlType.BOOL;
        }
        return arithType(op, lt, rt, b.left(), b.right(), b.line());
    }

    /** Unifies scalar operands for comparisons, coercing int literals. */
    private void matchNumeric(Bin b, CearlType lt, CearlType rt) {
        if (lt.equals(rt) && lt.isNumeric()) {
            return;
        }
        if (b.left() instanceof IntLit && rt.isNumeric()) {
            exprTypes.put(b.left(), rt);
            return;
        }
        if (b.right() instanceof IntLit && lt.isNumeric()) {
            exprTypes.put(b.right(), lt);
            return;
        }
        throw err(b.line(), "cannot compare " + lt.display() + " with " + rt.display());
    }

    private CearlType arithType(String op, CearlType lt, CearlType rt,
                                Expr left, Expr right, int line) {
        // Int-literal adaptation.
        if (!lt.equals(rt)) {
            if (left instanceof IntLit && literalAdapts(rt)) {
                exprTypes.put(left, scalarOf(rt));
                lt = scalarOf(rt);
            } else if (right instanceof IntLit && literalAdapts(lt)) {
                exprTypes.put(right, scalarOf(lt));
                rt = scalarOf(lt);
            }
        }

        if (op.equals("%")) {
            if (lt.equals(rt) && lt.isInteger()) {
                return lt;
            }
            throw err(line, "'%' works on matching integers — for floats use mod(a, b)");
        }
        // Same scalar type.
        if (lt.equals(rt) && lt.isNumeric()) {
            return lt;
        }
        // Vector cases.
        if (lt instanceof CearlType.Vec lv) {
            if (lt.equals(rt)) {
                return lt;
            }
            if (rt.equals(new CearlType.Prim(lv.elem()))) {
                return lt;
            }
        }
        if (rt instanceof CearlType.Vec rv && lt.equals(new CearlType.Prim(rv.elem()))) {
            return rt;
        }
        throw err(line, "'" + op + "' cannot combine " + lt.display() + " and " + rt.display()
            + (lt.isNumeric() && rt.isNumeric()
                ? " — CEARL has no implicit conversions; cast with "
                    + lt.display() + "(...) or " + rt.display() + "(...)"
                : ""));
    }

    private static boolean literalAdapts(CearlType target) {
        if (target instanceof CearlType.Vec v) {
            return v.elem() == PrimKind.F32 || v.elem() == PrimKind.U32 || v.elem() == PrimKind.I32;
        }
        return target.is(PrimKind.F32) || target.is(PrimKind.U32) || target.is(PrimKind.I64)
            || target.is(PrimKind.I32);
    }

    private static CearlType scalarOf(CearlType t) {
        return t instanceof CearlType.Vec v ? new CearlType.Prim(v.elem()) : t;
    }

    /** Coerces an int literal toward the expected type; errors otherwise. */
    private void coerce(Expr expr, CearlType actual, CearlType expected, int line, String what) {
        if (actual.equals(expected)) {
            return;
        }
        if (expr instanceof IntLit && literalAdapts(expected)
                && !(expected instanceof CearlType.Vec)) {
            exprTypes.put(expr, expected);
            return;
        }
        if (actual.is(PrimKind.I32) && expected.is(PrimKind.I64)) {
            return; // Host-side widening (consts/plans only; i64 never reaches device code).
        }
        throw err(line, "the " + what + " must be " + expected.display()
            + " (got " + actual.display() + ")"
            + (actual.isNumeric() && expected.isNumeric()
                ? " — cast explicitly with " + expected.display() + "(...)" : ""));
    }

    private void requireBool(Expr expr, CearlType t, String what) {
        if (!t.is(PrimKind.BOOL)) {
            throw err(expr.line(), "the " + what + " must be bool (got " + t.display() + ")");
        }
    }

    // ─── Calls ────────────────────────────────────────────────────────────

    private CearlType callType(Call c) {
        if (c.callee() instanceof Member m) {
            return methodCall(c, m);
        }
        Ident callee = (Ident) c.callee();
        String name = callee.name();

        CearlType builtinResult = builtinCall(c, name);
        if (builtinResult != null) {
            return builtinResult;
        }

        FnInfo fn = fns.get(name);
        if (fn == null) {
            throw err(c.line(), "unknown craft '" + name + "'"
                + (structs.containsKey(name) ? " — form values are built field-by-field"
                    + " with a flux declaration; constructors are not supported yet" : ""));
        }
        checkArgs(c, name, fn);
        recordCall(fn.key());
        return fn.ret();
    }

    private CearlType methodCall(Call c, Member m) {
        CearlType target = checkExprIn(m.target(), Mode.READ);
        if (!(target instanceof CearlType.Struct s)) {
            throw err(c.line(), "'." + m.name() + "(...)' needs a form value on the left"
                + " (got " + target.display() + ")");
        }
        FnInfo method = structs.get(s.name()).methods().get(m.name());
        if (method == null) {
            throw err(c.line(), "form " + s.name() + " has no method '" + m.name()
                + "' — methods: " + structs.get(s.name()).methods().keySet());
        }
        if (!isLvalueExpr(m.target())) {
            throw err(c.line(), "methods need an addressable receiver"
                + " — store the value in a local first");
        }
        if (method.mutatesSelf()) {
            // Re-check the receiver as a write target for the mutability rules.
            checkExprIn(m.target(), Mode.READ_WRITE);
        }
        checkArgs(c, s.name() + "." + m.name(), method);
        recordCall(method.key());
        return method.ret();
    }

    private void checkArgs(Call c, String name, FnInfo fn) {
        if (c.args().size() != fn.paramTypes().size()) {
            throw err(c.line(), "'" + name + "' takes " + fn.paramTypes().size()
                + " argument(s) but got " + c.args().size());
        }
        for (int i = 0; i < c.args().size(); i++) {
            CearlType t = checkExpr(c.args().get(i));
            coerce(c.args().get(i), t, fn.paramTypes().get(i), c.line(),
                "argument " + (i + 1) + " of '" + name + "'");
        }
    }

    private void recordCall(String calleeKey) {
        if (currentFnKey != null) {
            callGraph.computeIfAbsent(currentFnKey, k -> new HashSet<>()).add(calleeKey);
        }
    }

    private static boolean isLvalueExpr(Expr e) {
        return switch (e) {
            case Ident i -> true;
            case Member m -> isLvalueExpr(m.target());
            case Index i -> true;
            default -> false;
        };
    }

    // Builtin calls: returns null when the name isn't a builtin.
    private CearlType builtinCall(Call c, String name) {
        CearlType ctorType = CearlType.builtin(name);
        if (ctorType != null) {
            return constructorCall(c, name, ctorType);
        }
        return switch (name) {
            case "count" -> {
                if (c.args().size() != 1 || !(c.args().getFirst() instanceof Ident id)
                        || !(lookup(id.name()) instanceof Local l)
                        || !(l.type() instanceof CearlType.Array)) {
                    throw err(c.line(), "count(...) takes exactly one kernel take buffer");
                }
                exprTypes.put(c.args().getFirst(), l.type());
                yield CearlType.U32;
            }
            case "atomic_add", "atomic_min", "atomic_max" -> atomicCall(c, name);
            case "pick" -> {
                // Branchless select: pick(cond, a, b) — GLSL's ternary, CUDA's ?:.
                requireArgCount(c, name, 3);
                CearlType cond = checkExpr(c.args().getFirst());
                requireBool(c.args().getFirst(), cond, "first argument of pick");
                CearlType a = checkExpr(c.args().get(1));
                CearlType b = checkExpr(c.args().get(2));
                if (!a.equals(b)) {
                    if (c.args().get(1) instanceof IntLit && literalAdapts(b)) {
                        exprTypes.put(c.args().get(1), scalarOf(b));
                        a = b;
                    } else if (c.args().get(2) instanceof IntLit && literalAdapts(a)) {
                        exprTypes.put(c.args().get(2), scalarOf(a));
                        b = a;
                    } else {
                        throw err(c.line(), "pick(...) branches must match: "
                            + a.display() + " vs " + b.display());
                    }
                }
                if (!a.isNumeric() && !(a instanceof CearlType.Vec) && !a.is(PrimKind.BOOL)) {
                    throw err(c.line(), "pick(...) selects numbers, vectors, or bools"
                        + " (got " + a.display() + ")");
                }
                yield a;
            }
            case "barrier" -> {
                requireArgCount(c, name, 0);
                yield CearlType.VOID;
            }
            case "dot" -> {
                CearlType a = requireFloatVecArgs(c, name, 2);
                yield CearlType.F32;
            }
            case "cross" -> {
                CearlType a = requireFloatVecArgs(c, name, 2);
                if (!(a instanceof CearlType.Vec v) || v.size() != 3) {
                    throw err(c.line(), "cross(...) needs vec3 operands");
                }
                yield a;
            }
            case "length" -> {
                CearlType a = requireFloatVecArgs(c, name, 1);
                yield CearlType.F32;
            }
            case "normalize" -> requireFloatVecArgs(c, name, 1);
            case "floor", "ceil", "sqrt", "exp", "log", "sin", "cos" -> {
                requireArgCount(c, name, 1);
                CearlType a = checkFloatish(c.args().getFirst(), name);
                yield a;
            }
            case "abs" -> {
                requireArgCount(c, name, 1);
                CearlType a = checkExpr(c.args().getFirst());
                if (a.is(PrimKind.F32) || a.is(PrimKind.I32) || a instanceof CearlType.Vec) {
                    yield a;
                }
                throw err(c.line(), "abs(...) needs f32, i32, or a vector (got " + a.display() + ")");
            }
            case "pow", "mod" -> {
                requireArgCount(c, name, 2);
                CearlType a = checkFloatish(c.args().getFirst(), name);
                CearlType b = checkExpr(c.args().get(1));
                coerceToward(c.args().get(1), b, a, c.line(), name);
                yield a;
            }
            case "min", "max" -> minMaxCall(c, name);
            case "clamp" -> {
                requireArgCount(c, name, 3);
                CearlType x = checkExpr(c.args().getFirst());
                if (!x.isNumeric() && !(x instanceof CearlType.Vec)) {
                    throw err(c.line(), "clamp(...) needs a numeric or vector first argument");
                }
                CearlType bound = scalarOf(x);
                for (int i = 1; i < 3; i++) {
                    CearlType t = checkExpr(c.args().get(i));
                    if (!t.equals(x) && !t.equals(bound)) {
                        coerceToward(c.args().get(i), t, bound, c.line(), name);
                    }
                }
                yield x;
            }
            case "mix" -> {
                requireArgCount(c, name, 3);
                CearlType a = checkFloatish(c.args().getFirst(), name);
                CearlType b = checkExpr(c.args().get(1));
                coerceToward(c.args().get(1), b, a, c.line(), name);
                CearlType t = checkExpr(c.args().get(2));
                if (!t.equals(a) && !t.is(PrimKind.F32)) {
                    coerceToward(c.args().get(2), t, CearlType.F32, c.line(), name);
                }
                yield a;
            }
            default -> null;
        };
    }

    private CearlType constructorCall(Call c, String name, CearlType type) {
        if (type instanceof CearlType.Prim p) {
            // Casts: f32(x), i32(x), u32(x). i64/bool/void are not castable-to.
            if (p.kind() != PrimKind.F32 && p.kind() != PrimKind.I32 && p.kind() != PrimKind.U32) {
                throw err(c.line(), "cannot cast to " + type.display());
            }
            requireArgCount(c, name, 1);
            CearlType a = checkExpr(c.args().getFirst());
            if (!a.isNumeric() && !a.is(PrimKind.BOOL)) {
                throw err(c.line(), name + "(...) casts numbers and bools (got "
                    + a.display() + ")");
            }
            if (a.is(PrimKind.I64)) {
                throw err(c.line(), "i64 values cannot appear in device code");
            }
            return type;
        }
        CearlType.Vec v = (CearlType.Vec) type;
        CearlType elem = new CearlType.Prim(v.elem());
        List<Expr> args = c.args();
        if (args.isEmpty()) {
            throw err(c.line(), name + "(...) needs arguments");
        }
        int components = 0;
        for (Expr arg : args) {
            CearlType t = checkExpr(arg);
            if (t.equals(elem)) {
                components += 1;
            } else if (arg instanceof IntLit && literalAdapts(elem)) {
                exprTypes.put(arg, elem);
                components += 1;
            } else if (t instanceof CearlType.Vec av && av.elem() == v.elem()) {
                components += av.size();
            } else {
                throw err(c.line(), name + "(...) components must be " + elem.display()
                    + " or " + (v.elem() == PrimKind.F32 ? "float" : "matching") + " vectors"
                    + " (got " + t.display() + ")");
            }
        }
        if (components != v.size() && !(args.size() == 1 && components == 1)) {
            throw err(c.line(), name + "(...) needs " + v.size()
                + " components (or one scalar to broadcast); got " + components);
        }
        return type;
    }

    private CearlType atomicCall(Call c, String name) {
        requireArgCount(c, name, 2);
        Expr target = c.args().getFirst();
        // The target must live in an inout buffer: atomics read AND write.
        Expr root = target;
        while (root instanceof Member m) {
            root = m.target();
        }
        if (!(root instanceof Index ix) || !(ix.target() instanceof Ident id)
                || !(lookup(id.name()) instanceof Local l)
                || !(l.type() instanceof CearlType.Array)) {
            throw err(c.line(), name + "(...) needs a buffer or shared element as its"
                + " first argument (e.g. counts[i])");
        }
        if (l.storage() == Storage.UNIFORM_ARRAY) {
            throw err(c.line(), "uniform arrays are read-only — atomics need a buffer"
                + " or shared array");
        }
        if (l.storage() == Storage.BUFFER && l.bufferDir() != Dir.INOUT) {
            throw err(c.line(), "atomics read and write — buffer '" + id.name()
                + "' must be a take marked 'inout'");
        }
        CearlType t = checkExprIn(target, Mode.READ_WRITE);
        if (!t.is(PrimKind.I32) && !t.is(PrimKind.U32)) {
            throw err(c.line(), name + "(...) works on i32/u32 elements (got "
                + t.display() + ")");
        }
        CearlType vt = checkExpr(c.args().get(1));
        coerce(c.args().get(1), vt, t, c.line(), "second argument of " + name);
        return t;
    }

    private CearlType minMaxCall(Call c, String name) {
        requireArgCount(c, name, 2);
        CearlType a = checkExpr(c.args().getFirst());
        CearlType b = checkExpr(c.args().get(1));
        if (a.equals(b) && (a.isNumeric() || a instanceof CearlType.Vec)) {
            return a;
        }
        if (a instanceof CearlType.Vec av && b.equals(new CearlType.Prim(av.elem()))) {
            return a;
        }
        if (c.args().getFirst() instanceof IntLit && literalAdapts(b)) {
            exprTypes.put(c.args().getFirst(), scalarOf(b));
            return b;
        }
        if (c.args().get(1) instanceof IntLit && literalAdapts(a)) {
            exprTypes.put(c.args().get(1), scalarOf(a));
            return a;
        }
        throw err(c.line(), name + "(...) needs matching numeric operands (got "
            + a.display() + " and " + b.display() + ")");
    }

    private CearlType checkFloatish(Expr e, String name) {
        CearlType t = checkExpr(e);
        if (t.is(PrimKind.F32) || t.isFloatVec()) {
            return t;
        }
        if (e instanceof IntLit) {
            exprTypes.put(e, CearlType.F32);
            return CearlType.F32;
        }
        throw err(e.line(), name + "(...) needs f32 or a float vector (got " + t.display() + ")");
    }

    private void coerceToward(Expr e, CearlType actual, CearlType expected, int line, String name) {
        if (actual.equals(expected)) {
            return;
        }
        if (e instanceof IntLit && literalAdapts(expected)) {
            exprTypes.put(e, scalarOf(expected));
            return;
        }
        throw err(line, name + "(...) argument type mismatch: expected " + expected.display()
            + ", got " + actual.display());
    }

    private void requireArgCount(Call c, String name, int n) {
        if (c.args().size() != n) {
            throw err(c.line(), name + "(...) takes " + n + " argument(s), got " + c.args().size());
        }
    }

    private CearlType requireFloatVecArgs(Call c, String name, int n) {
        requireArgCount(c, name, n);
        CearlType first = checkExpr(c.args().getFirst());
        if (!first.isFloatVec()) {
            throw err(c.line(), name + "(...) needs float vectors (got " + first.display() + ")");
        }
        for (int i = 1; i < n; i++) {
            CearlType t = checkExpr(c.args().get(i));
            if (!t.equals(first)) {
                throw err(c.line(), name + "(...) operands must match: " + first.display()
                    + " vs " + t.display());
            }
        }
        return first;
    }

    // ─── Recursion detection ──────────────────────────────────────────────

    private void detectRecursion() {
        Set<String> done = new HashSet<>();
        for (String key : callGraph.keySet()) {
            recursionDfs(key, new ArrayList<>(), done);
        }
    }

    private void recursionDfs(String key, List<String> path, Set<String> done) {
        if (done.contains(key)) {
            return;
        }
        int at = path.indexOf(key);
        if (at >= 0) {
            throw err(0, "recursion is not supported on the GPU target: "
                + String.join(" -> ", path.subList(at, path.size())) + " -> " + key);
        }
        path.add(key);
        for (String callee : callGraph.getOrDefault(key, Set.of())) {
            recursionDfs(callee, path, done);
        }
        path.removeLast();
        done.add(key);
    }

    // ─── Shared plumbing ──────────────────────────────────────────────────

    private CearlType resolveType(TypeRef ref) {
        CearlType t = CearlType.builtin(ref.name());
        if (t == null) {
            if (structs.containsKey(ref.name())) {
                t = new CearlType.Struct(ref.name());
            } else {
                throw err(ref.line(), "unknown type '" + ref.name() + "'"
                    + " — builtins are bool/i32/u32/i64/f32/vecN/ivecN/uvecN,"
                    + " and structs must be declared before use by name");
            }
        }
        return ref.array() ? new CearlType.Array(t) : t;
    }

    private void requireDeviceType(CearlType t, int line, String what) {
        if (t.is(PrimKind.I64)) {
            throw err(line, "i64 is host-only (plans and consts) — " + what
                + " cannot be i64 because GLSL 430 has no 64-bit integers");
        }
        if (t.is(PrimKind.VOID)) {
            throw err(line, what + " cannot be void");
        }
    }

    private void checkName(String name, String what, int line) {
        if (GPU_RESERVED.contains(name)) {
            throw err(line, "'" + name + "' is reserved on the GPU target"
                + " — pick another " + what + " name");
        }
        if (name.startsWith("gl_") || name.startsWith("cearl_")) {
            throw err(line, what + " names may not start with 'gl_' or 'cearl_'");
        }
    }

    private void pushScope() {
        scopes.push(new HashMap<>());
    }

    private void popScope() {
        scopes.pop();
    }

    private void declare(String name, Local local, int line) {
        if (currentOwnerFields != null && currentOwnerFields.containsKey(name)) {
            throw err(line, "'" + name + "' shadows a field of form '" + currentOwnerName
                + "' — fields are directly visible inside methods, pick another name");
        }
        if (lookup(name) != null || consts.containsKey(name) || BUILTIN_VARS.contains(name)) {
            throw err(line, "'" + name + "' is already defined — shadowing is not allowed");
        }
        scopes.peek().put(name, local);
    }

    private Local lookup(String name) {
        for (Map<String, Local> scope : scopes) {
            Local l = scope.get(name);
            if (l != null) {
                return l;
            }
        }
        return null;
    }

    private static String fnKey(String owner, String name) {
        return owner == null ? name : owner + "." + name;
    }

    private CearlException err(int line, String message) {
        return new CearlException(sourceName, line, 0, message);
    }
}
