package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The draft of a scope's two-way edits (#289). Draft, validation, commit and rollback are
 * separate steps:
 * <ol>
 *   <li>{@link #stage} checks one edit (schema, then the root's {@link EditPolicy.Validator}) and
 *       keeps it in the draft. The source is untouched; everything bound to it in this scope now
 *       reads the drafted value.</li>
 *   <li>{@link #apply} sends the drafted root to its commit action; the host validates and saves.
 *       On success the applied edits leave the draft (the source now holds them); on failure
 *       they stay, with {@link #lastError}.</li>
 *   <li>{@link #cancel} drops the draft: bound elements show the committed values again.</li>
 * </ol>
 * A text field stages on commit (Enter or blur), never per keystroke, and nothing is saved
 * until {@code apply}.
 *
 * <p><b>The draft follows the source.</b> The session keeps the staged members, not a copy of
 * the root: the drafted value is always the source's current value with the staged members
 * laid over it. A change made elsewhere while this screen edits (a hotkey, another screen's
 * apply, a server echo) shows through every member this screen has not touched, and
 * {@code apply} never writes an old value back over it. A member that was staged here and has
 * since changed in the source is a {@link #conflicts conflict}: the staged value still wins on
 * apply, and the screen can show or resolve it. When the commit action declares a
 * {@code changed} parameter it also receives the staged member paths, so the host can save only
 * those.
 */
public final class EditSession {

    /** Outcome of {@link #stage}. */
    public record Result(boolean accepted, String problem) {
        static final Result OK = new Result(true, null);

        static Result invalid(String problem) {
            return new Result(false, problem);
        }
    }

    /** One staged member: its value and the committed value it replaced when it was staged. */
    private record Staged(UiValue value, UiValue base) {
    }

    /** The staged members of one root, plus a memo of the last drafted value. */
    private static final class Draft {
        final LinkedHashMap<DataPath, Staged> staged = new LinkedHashMap<>();
        UiValue memoSource;
        UiValue memoDraft;
        boolean memoValid;

        void invalidate() {
            memoValid = false;
        }
    }

    private final UiScope scope;
    private final Map<String, Draft> drafts = new LinkedHashMap<>();
    private final Map<String, String> errors = new HashMap<>();

    EditSession(UiScope scope) {
        this.scope = scope;
    }

    /** Stages {@code value} at an absolute path of an editable root. */
    public Result stage(DataPath path, UiValue value, CallSite site) {
        if (scope.isClosed()) {
            return Result.invalid("screen closed");
        }
        DataRoot root = scope.host().data().root(path.rootName());
        if (root == null) {
            return reject(site, "no data root '" + path.rootName() + "'");
        }
        if (!scope.declares(root.name())) {
            return reject(site, "CAPABILITY_MISSING: " + scope.undeclared(path));
        }
        if (!root.editable()) {
            return reject(site, path.rootName() + " is read-only");
        }
        DataType type = path.typeFrom(root.source().type());
        if (type == null) {
            return reject(site, path + " is not in the schema of " + root.name());
        }
        String problem = type.problem(value, path.toString());
        if (problem != null) {
            return reject(site, problem);
        }
        UiValue committed = committed(root.name());
        if (committed == null) {
            return reject(site, root.name() + " is not loaded");
        }
        UiValue base = draft(root.name());
        UiValue next = path.with(base == null ? committed : base, value);
        problem = root.edit().validator().problem(path, value, next);
        if (problem != null) {
            return reject(site, problem);
        }
        Draft d = drafts.computeIfAbsent(root.name(), n -> new Draft());
        // A member staged again, or below this one, is replaced by this edit.
        d.staged.keySet().removeIf(path::contains);
        d.staged.put(path, new Staged(value, path.evaluate(committed)));
        prune(d, committed);
        d.invalidate();
        if (d.staged.isEmpty()) {
            drafts.remove(root.name());
        }
        errors.remove(root.name());
        scope.refresh(root.name());
        return Result.OK;
    }

    /** Drops staged members that only restate the source (no staged ancestor overrides them). */
    private static void prune(Draft d, UiValue committed) {
        List<DataPath> paths = new ArrayList<>(d.staged.keySet());
        for (DataPath p : paths) {
            Staged s = d.staged.get(p);
            boolean underAncestor = paths.stream().anyMatch(q -> q != p && q.contains(p) && d.staged.containsKey(q));
            if (!underAncestor && s.value().equals(p.evaluate(committed))) {
                d.staged.remove(p);
            }
        }
    }

    private Result reject(CallSite site, String problem) {
        scope.problem(new UiProblem(UiProblem.Kind.INVALID_EDIT, site, problem));
        return Result.invalid(problem);
    }

    private UiValue committed(String root) {
        DataRoot r = scope.host().data().root(root);
        return r == null ? null : r.source().state().valueOrNull();
    }

    /**
     * The drafted value of {@code root}: the source's current value with this scope's staged
     * members laid over it, or {@code null} when nothing staged differs from the source.
     */
    public UiValue draft(String root) {
        Draft d = drafts.get(root);
        if (d == null) {
            return null;
        }
        UiValue committed = committed(root);
        if (d.memoValid && d.memoSource == committed) {
            return d.memoDraft;
        }
        UiValue out = null;
        if (committed != null) {
            UiValue v = committed;
            for (Map.Entry<DataPath, Staged> e : d.staged.entrySet()) {
                try {
                    v = e.getKey().with(v, e.getValue().value());
                } catch (IllegalArgumentException gone) {
                    // The member no longer exists in the source (a list shrank): nothing to overlay.
                }
            }
            out = v.equals(committed) ? null : v;
        }
        d.memoSource = committed;
        d.memoDraft = out;
        d.memoValid = true;
        return out;
    }

    public boolean isDirty() {
        return !dirtyRoots().isEmpty();
    }

    public List<String> dirtyRoots() {
        List<String> out = new ArrayList<>();
        for (String root : drafts.keySet()) {
            if (draft(root) != null) {
                out.add(root);
            }
        }
        return out;
    }

    /** Paths staged under {@code root}, in staging order. */
    public List<DataPath> stagedPaths(String root) {
        Draft d = drafts.get(root);
        return d == null ? List.of() : List.copyOf(d.staged.keySet());
    }

    /**
     * Staged members whose source value changed after they were staged (someone else edited the
     * same setting). The staged value still wins on {@link #apply}; a screen can show these or
     * {@link #cancel(String, DataPath) drop} them to take the source's value.
     */
    public List<DataPath> conflicts(String root) {
        Draft d = drafts.get(root);
        UiValue committed = committed(root);
        if (d == null || committed == null) {
            return List.of();
        }
        List<DataPath> out = new ArrayList<>();
        d.staged.forEach((p, s) -> {
            UiValue now = p.evaluate(committed);
            if (!java.util.Objects.equals(now, s.base()) && !s.value().equals(now)) {
                out.add(p);
            }
        });
        return out;
    }

    /** Why the last {@link #apply} of {@code root} failed, or {@code null}. */
    public String lastError(String root) {
        return errors.get(root);
    }

    /**
     * Commits {@code root}'s draft through its commit action: parameters {@code {value: <the
     * drafted root>}}, plus {@code changed: [<staged member paths>]} when the action's parameter
     * schema declares {@code changed}.
     *
     * @return the call, or {@code null} when the root has nothing to apply
     */
    public ActionCall apply(String root, CallSite site) {
        UiValue draft = draft(root);
        if (draft == null) {
            return null;
        }
        Draft d = drafts.get(root);
        Map<DataPath, Staged> applied = new LinkedHashMap<>(d.staged);
        DataRoot r = scope.host().data().root(root);
        String actionId = r.edit().commitAction();
        Map<String, UiValue> args = new LinkedHashMap<>();
        args.put("value", draft);
        ActionRegistry.Entry entry = scope.host().actions().entry(actionId);
        if (entry != null && entry.spec().params().child("changed") != null) {
            List<UiValue> changed = new ArrayList<>();
            applied.keySet().forEach(p -> changed.add(UiValue.of(p.toString())));
            args.put("changed", new UiValue.Arr(changed));
        }
        ActionCall call = scope.invoke(actionId, new UiValue.Obj(args), site);
        call.whenSettled(c -> {
            switch (c.state()) {
                case SUCCEEDED -> {
                    Draft now = drafts.get(root);
                    if (now != null) {
                        // Edits staged while the commit ran stay drafted.
                        for (Iterator<Map.Entry<DataPath, Staged>> it = now.staged.entrySet().iterator(); it.hasNext(); ) {
                            Map.Entry<DataPath, Staged> e = it.next();
                            if (applied.get(e.getKey()) == e.getValue()) {
                                it.remove();
                            }
                        }
                        now.invalidate();
                        if (now.staged.isEmpty()) {
                            drafts.remove(root);
                        }
                    }
                    errors.remove(root);
                    scope.refresh(root);
                }
                case FAILED -> errors.put(root, String.valueOf(c.error() == null ? "failed" : c.error().getMessage()));
                default -> {
                }
            }
        });
        return call;
    }

    /** {@link #apply} for every root with edits. */
    public List<ActionCall> applyAll(CallSite site) {
        List<ActionCall> out = new ArrayList<>();
        for (String root : dirtyRoots()) {
            ActionCall c = apply(root, site);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    /** Rollback: drops every drafted edit; bound elements show the committed values again. */
    public void cancel() {
        List<String> roots = List.copyOf(drafts.keySet());
        drafts.clear();
        errors.clear();
        roots.forEach(scope::refresh);
    }

    /** Rollback of one root. */
    public void cancel(String root) {
        errors.remove(root);
        if (drafts.remove(root) != null) {
            scope.refresh(root);
        }
    }

    /** Rollback of one staged member (and anything staged below it): the source's value shows again. */
    public void cancel(String root, DataPath path) {
        Draft d = drafts.get(root);
        if (d != null && d.staged.keySet().removeIf(path::contains)) {
            d.invalidate();
            if (d.staged.isEmpty()) {
                drafts.remove(root);
            }
            scope.refresh(root);
        }
    }

    void discard() {
        drafts.clear();
        errors.clear();
    }
}
