package com.openmason.engine.cearl;

import com.openmason.engine.cearl.CearlAst.ArenaDecl;
import com.openmason.engine.cearl.CearlAst.KernelDecl;
import com.openmason.engine.cearl.CearlAst.PlanDecl;
import com.openmason.engine.cearl.CearlAst.PoolDecl;
import com.openmason.engine.cearl.CearlAst.PressureRule;
import com.openmason.engine.cearl.CearlAst.Program;
import com.openmason.engine.cearl.CearlAst.WhenDecl;
import com.openmason.engine.cearl.CearlChecker.Checked;
import com.openmason.engine.cearl.CearlChecker.ConstInfo;
import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.vram.VramArenaPolicy;
import com.openmason.engine.vram.VramPlan;
import com.openmason.engine.vram.VramPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The CEARL compiler entry point: source → {@link CearlProgram}.
 *
 * <p>Pipeline: {@link CearlLexer lex} → {@link CearlParser parse} →
 * {@link CearlChecker type-check} → {@link GlslEmitter lower kernels to GLSL}
 * → resolve the plan (pool inheritance, {@code when} guards evaluated against
 * the host environment, percent budgets against the device budget, validation).
 *
 * <p>The {@code env} map is the host facts {@code when} guards may read —
 * e.g. {@code vram} (detected VRAM in bytes, 0 when unknown). Every failure
 * is a {@link CearlException} with a source location and a teaching message.
 */
public final class CearlCompiler {

    private CearlCompiler() {
    }

    public static CearlProgram compile(String source, String sourceName, Map<String, Long> env) {
        Program program = CearlParser.parse(source, sourceName);
        Checked checked = CearlChecker.check(program, sourceName, env);

        LinkedHashMap<String, CearlKernel> kernels = new LinkedHashMap<>();
        for (KernelDecl k : program.kernels()) {
            kernels.put(k.name(), GlslEmitter.emit(checked, k));
        }

        VramPlan plan = program.plan() == null ? null
            : new PlanBuilder(sourceName, checked, env).build(program.plan());
        return new CearlProgram(sourceName, plan, kernels);
    }

    // ─── Plan resolution ──────────────────────────────────────────────────

    private static final class PlanBuilder {

        private final String sourceName;
        private final CearlConstEval eval;

        PlanBuilder(String sourceName, Checked checked, Map<String, Long> env) {
            Map<String, Object> constValues = new LinkedHashMap<>();
            for (Map.Entry<String, ConstInfo> e : checked.consts().entrySet()) {
                constValues.put(e.getKey(), e.getValue().value());
            }
            this.sourceName = sourceName;
            this.eval = new CearlConstEval(sourceName, constValues, env);
        }

        VramPlan build(PlanDecl decl) {
            long deviceBudget = 0;
            if (decl.device() != null && decl.device().budget() != null) {
                Object v = eval.eval(decl.device().budget());
                if (!(v instanceof Long bytes) || bytes < 0) {
                    throw err(decl.device().line(), "the device budget must resolve to a"
                        + " byte count — sizes, pins, environment names, and 'otherwise'");
                }
                deviceBudget = bytes;
            }
            double headroom = decl.device() == null || decl.device().headroom() < 0 ? 0
                : decl.device().headroom();
            if (headroom > 0.9) {
                throw err(decl.device().line(),
                    "headroom above 90% leaves no usable budget");
            }

            // 1. Base pools, then matching when-blocks overlay in order.
            LinkedHashMap<String, PoolDecl> drafts = new LinkedHashMap<>();
            for (PoolDecl p : decl.pools()) {
                drafts.put(p.name(), p);
            }
            for (WhenDecl when : decl.whens()) {
                if (!eval.evalBool(when.condition(), "when guard")) {
                    continue;
                }
                for (PoolDecl override : when.pools()) {
                    PoolDecl base = drafts.get(override.name());
                    drafts.put(override.name(), base == null ? override
                        : overlay(base, override));
                }
            }

            // 2. Resolve single inheritance (parent chains, cycle-checked).
            LinkedHashMap<String, PoolDecl> resolved = new LinkedHashMap<>();
            for (String name : drafts.keySet()) {
                resolveInheritance(name, drafts, resolved, new ArrayList<>());
            }

            // 3. Materialize into runtime pools, resolving percent budgets.
            LinkedHashMap<String, VramPool> pools = new LinkedHashMap<>();
            long budgetSum = 0;
            for (PoolDecl p : resolved.values()) {
                VramPool pool = materialize(p, deviceBudget);
                pools.put(pool.name(), pool);
                budgetSum += pool.budgetBytes();
            }
            if (deviceBudget > 0 && budgetSum > deviceBudget) {
                throw err(decl.line(), "pool budgets add up to " + mib(budgetSum)
                    + " MiB, above the device budget of " + mib(deviceBudget)
                    + " MiB — shrink shares or raise the budget");
            }

            // 4. Pressure rules: thresholds sane, shed targets exist.
            List<VramPlan.PressureRule> rules = new ArrayList<>();
            for (PressureRule r : decl.rules()) {
                if (r.threshold() <= 0 || r.threshold() >= 2.0) {
                    throw err(r.line(), "pressure threshold must be between 0% and 200%");
                }
                if (deviceBudget == 0) {
                    throw err(r.line(), "'on pressure' needs a device budget"
                        + " — pressure is measured against it");
                }
                for (String shed : r.shed()) {
                    if (!pools.containsKey(shed)) {
                        throw err(r.line(), "'shed " + shed + "' names an unknown pool"
                            + " — pools: " + pools.keySet());
                    }
                }
                rules.add(new VramPlan.PressureRule(r.threshold(), List.copyOf(r.shed())));
            }

            return new VramPlan(decl.name(), deviceBudget, headroom, pools, rules);
        }

        /** Child fields win; unset child fields inherit from the base. */
        private PoolDecl overlay(PoolDecl base, PoolDecl child) {
            boolean childHasBudget = child.budgetBytes() >= 0 || child.budgetShare() >= 0;
            return new PoolDecl(base.name(),
                child.parent() != null ? child.parent() : base.parent(),
                child.category() != null ? child.category() : base.category(),
                childHasBudget ? child.budgetBytes() : base.budgetBytes(),
                childHasBudget ? child.budgetShare() : base.budgetShare(),
                child.priority() != PoolDecl.PRIORITY_UNSET ? child.priority() : base.priority(),
                child.storage() != null ? child.storage() : base.storage(),
                child.grow() != null ? child.grow() : base.grow(),
                overlayArena(base.arena(), child.arena()),
                child.line());
        }

        private ArenaDecl overlayArena(ArenaDecl base, ArenaDecl child) {
            if (base == null) {
                return child;
            }
            if (child == null) {
                return base;
            }
            return new ArenaDecl(
                child.vertexBytes() >= 0 ? child.vertexBytes() : base.vertexBytes(),
                child.indexBytes() >= 0 ? child.indexBytes() : base.indexBytes(),
                child.growth() >= 0 ? child.growth() : base.growth(),
                child.reserve() >= 0 ? child.reserve() : base.reserve(),
                child.align() >= 0 ? child.align() : base.align(),
                child.trim() >= 0 ? child.trim() : base.trim(),
                child.line());
        }

        private void resolveInheritance(String name, Map<String, PoolDecl> drafts,
                                        LinkedHashMap<String, PoolDecl> resolved,
                                        List<String> path) {
            if (resolved.containsKey(name)) {
                return;
            }
            PoolDecl draft = drafts.get(name);
            int at = path.indexOf(name);
            if (at >= 0) {
                throw err(draft.line(), "pool inheritance cycle: "
                    + String.join(" -> ", path.subList(at, path.size())) + " -> " + name);
            }
            if (draft.parent() != null) {
                PoolDecl parent = drafts.get(draft.parent());
                if (parent == null) {
                    throw err(draft.line(), "pool '" + name + "' inherits from unknown pool '"
                        + draft.parent() + "' — pools: " + drafts.keySet());
                }
                path.add(name);
                resolveInheritance(parent.name(), drafts, resolved, path);
                path.removeLast();
                // Inherit everything except the identity and the budget — a
                // child sharing its parent's budget would double-count it.
                PoolDecl resolvedParent = resolved.get(parent.name());
                PoolDecl merged = overlay(new PoolDecl(name, null, resolvedParent.category(),
                        -1, -1, resolvedParent.priority(), resolvedParent.storage(),
                        resolvedParent.grow(), resolvedParent.arena(), draft.line()),
                    draft);
                resolved.put(name, merged);
            } else {
                resolved.put(name, draft);
            }
        }

        private VramPool materialize(PoolDecl p, long deviceBudget) {
            GpuMemoryTracker.Category category = null;
            if (p.category() != null) {
                try {
                    category = GpuMemoryTracker.Category.valueOf(p.category());
                } catch (IllegalArgumentException e) {
                    throw err(p.line(), "unknown category '" + p.category() + "' — expected one of "
                        + Arrays.toString(GpuMemoryTracker.Category.values()));
                }
            }
            long budget = 0;
            if (p.budgetShare() >= 0) {
                if (p.budgetShare() > 1.0) {
                    throw err(p.line(), "pool '" + p.name() + "' budget share exceeds 100%");
                }
                if (deviceBudget == 0) {
                    throw err(p.line(), "pool '" + p.name() + "' declares a percent budget"
                        + " but the plan has no device budget to take a share of");
                }
                budget = (long) (deviceBudget * p.budgetShare());
            } else if (p.budgetBytes() >= 0) {
                budget = p.budgetBytes();
            }

            VramPool.Storage storage = "persistent".equals(p.storage())
                ? VramPool.Storage.PERSISTENT : VramPool.Storage.STATIC;
            VramPool.Grow grow = "sparse".equals(p.grow())
                ? VramPool.Grow.SPARSE : VramPool.Grow.COPY;
            int priority = p.priority() == PoolDecl.PRIORITY_UNSET ? 50 : p.priority();
            if (priority < 0 || priority > 1000) {
                throw err(p.line(), "priority must be between 0 and 1000");
            }

            VramArenaPolicy arena = null;
            if (p.arena() != null) {
                arena = materializeArena(p.name(), p.arena(), grow == VramPool.Grow.SPARSE);
            }
            return new VramPool(p.name(), category, budget, priority, storage, grow, arena);
        }

        private VramArenaPolicy materializeArena(String pool, ArenaDecl a, boolean sparse) {
            if (a.vertexBytes() < 0 || a.indexBytes() < 0) {
                throw err(a.line(), "pool '" + pool + "' declares an arena without both"
                    + " 'vertex' and 'index' sizes (inheritance can supply them)");
            }
            double growth = a.growth() < 0 ? 1.75 : a.growth();
            double reserve = a.reserve() < 0 ? 0.25 : a.reserve();
            int align = a.align() < 0 ? 4 : a.align();
            double trim = a.trim() < 0 ? 0 : a.trim();
            if (growth <= 1.0 || growth > 8.0) {
                throw err(a.line(), "growth must be above 1.0 and at most 8.0"
                    + " (1.0 would never grow; got " + growth + ")");
            }
            if (reserve > 1.0) {
                throw err(a.line(), "reserve above 100% makes no sense");
            }
            if (align < 1 || align > 4096 || Integer.bitCount(align) != 1) {
                throw err(a.line(), "align must be a power of two between 1 and 4096");
            }
            if (trim > 0.9) {
                throw err(a.line(), "trim above 90% would shrink arenas that are"
                    + " nearly full — keep it well under the growth reserve's inverse");
            }
            return new VramArenaPolicy(a.vertexBytes(), a.indexBytes(), growth, reserve,
                align, trim, sparse);
        }

        private static long mib(long bytes) {
            return bytes >> 20;
        }

        private CearlException err(int line, String message) {
            return new CearlException(sourceName, line, 0, message);
        }
    }
}
