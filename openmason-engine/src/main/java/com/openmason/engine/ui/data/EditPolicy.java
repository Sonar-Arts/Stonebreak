package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;

import java.util.Objects;

/**
 * How a two-way bound root is edited (#289). Edits never write the source directly: they are
 * staged in the scope's {@link EditSession} draft, each checked by {@link #validator}, and only
 * {@link EditSession#apply} sends the drafted root to {@link #commitAction} (parameters
 * {@code {value: <root>}}), whose handler validates again and saves. Cancel drops the draft.
 *
 * @param commitAction id of the action that applies a drafted root
 * @param validator    per-edit check, {@code null} accepts anything the schema accepts
 */
public record EditPolicy(String commitAction, Validator validator) {

    /** Checks one staged edit against the whole draft (cross-field rules see the other drafted values). */
    @FunctionalInterface
    public interface Validator {
        /**
         * @param path  absolute path of the edited member
         * @param value the proposed value (already schema-checked)
         * @param draft the root's value with this edit applied
         * @return {@code null} when acceptable, else a message the UI can show
         */
        String problem(DataPath path, UiValue value, UiValue draft);
    }

    public EditPolicy {
        Objects.requireNonNull(commitAction, "commitAction");
        validator = validator == null ? (p, v, d) -> null : validator;
    }
}
