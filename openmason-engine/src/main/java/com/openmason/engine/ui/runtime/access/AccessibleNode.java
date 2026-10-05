package com.openmason.engine.ui.runtime.access;

import com.openmason.engine.ui.runtime.UiRect;

import java.util.List;
import java.util.Set;

/**
 * One node of the semantic tree a platform accessibility bridge would expose (#288). This is
 * metadata, not a screen reader: it is what a later bridge (or a test, or the editor's
 * inspector) reads.
 *
 * @param key         stable element key
 * @param role        semantic role ({@code button}, {@code text-field}, ...)
 * @param name        accessible name: {@code accessibleName}, else derived from text content
 * @param description {@code accessibleDescription}, else the tooltip
 * @param value       {@code accessibleValue}, else the widget's own value (a text field's text,
 *                    masked for passwords)
 * @param states      {@code focused}, {@code focusable}, {@code disabled}, {@code checked},
 *                    {@code invalid}, {@code read-only}, {@code modal}, and {@code status:<s>}
 * @param bounds      device-pixel rect
 * @param hints       action hints, {@code "<glyph>: <label>"}, for the current bindings
 */
public record AccessibleNode(String key, String role, String name, String description, String value,
                             Set<String> states, UiRect bounds, List<String> hints, List<AccessibleNode> children) {
}
