package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.HashMap;
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
 *       On success the draft is dropped (the source now holds the value); on failure it stays,
 *       with {@link #lastError}.</li>
 *   <li>{@link #cancel} drops the draft: bound elements show the committed values again.</li>
 * </ol>
 * A text field stages on commit (Enter or blur), never per keystroke, and nothing is saved
 * until {@code apply}.
 */
public final class EditSession {

    /** Outcome of {@link #stage}. */
    public record Result(boolean accepted, String problem) {
        static final Result OK = new Result(true, null);

        static Result invalid(String problem) {
            return new Result(false, problem);
        }
    }

    private final UiScope scope;
    private final Map<String, UiValue> drafts = new LinkedHashMap<>();
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
        UiValue committed = root.source().state().valueOrNull();
        UiValue base = drafts.getOrDefault(root.name(), committed);
        if (base == null) {
            return reject(site, root.name() + " is not loaded");
        }
        UiValue next = path.with(base, value);
        problem = root.edit().validator().problem(path, value, next);
        if (problem != null) {
            return reject(site, problem);
        }
        if (next.equals(committed)) {
            drafts.remove(root.name());
        } else {
            drafts.put(root.name(), next);
        }
        errors.remove(root.name());
        scope.refresh(root.name());
        return Result.OK;
    }

    private Result reject(CallSite site, String problem) {
        scope.problem(new UiProblem(UiProblem.Kind.INVALID_EDIT, site, problem));
        return Result.invalid(problem);
    }

    /** The drafted value of {@code root}, or {@code null} when it has no pending edits. */
    public UiValue draft(String root) {
        return drafts.get(root);
    }

    public boolean isDirty() {
        return !drafts.isEmpty();
    }

    public List<String> dirtyRoots() {
        return List.copyOf(drafts.keySet());
    }

    /** Why the last {@link #apply} of {@code root} failed, or {@code null}. */
    public String lastError(String root) {
        return errors.get(root);
    }

    /**
     * Commits {@code root}'s draft through its commit action.
     *
     * @return the call, or {@code null} when the root has nothing to apply
     */
    public ActionCall apply(String root, CallSite site) {
        UiValue draft = drafts.get(root);
        if (draft == null) {
            return null;
        }
        DataRoot r = scope.host().data().root(root);
        ActionCall call = scope.invoke(r.edit().commitAction(),
            new UiValue.Obj(Map.of("value", draft)), site);
        call.whenSettled(c -> {
            switch (c.state()) {
                case SUCCEEDED -> {
                    if (draft.equals(drafts.get(root))) {
                        drafts.remove(root); // edits staged meanwhile stay drafted
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
        List<String> roots = dirtyRoots();
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

    void discard() {
        drafts.clear();
        errors.clear();
    }
}
