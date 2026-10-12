package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.UiValue;

/**
 * Evaluates relative data paths ({@code .label}, {@code .items[2].count}, {@code .}) against a
 * static value. Used for component parameters, which are the data source of a component's
 * subtree; live host sources and change notification are #289's.
 */
final class DataPaths {

    private DataPaths() {
    }

    static boolean isRelative(String path) {
        return path.startsWith(".");
    }

    /** @return the value at {@code path}, or {@code null} when absent or the path is absolute */
    static UiValue eval(UiValue root, String path) {
        if (!isRelative(path) || root == null) {
            return null;
        }
        if (path.equals(".")) {
            return root;
        }
        UiValue cur = root;
        int i = 1;
        while (i < path.length() && cur != null) {
            char c = path.charAt(i);
            if (c == '.') {
                i++;
                continue;
            }
            if (c == '[') {
                int end = path.indexOf(']', i);
                if (end < 0) {
                    return null;
                }
                int index;
                try {
                    index = Integer.parseInt(path.substring(i + 1, end));
                } catch (NumberFormatException e) {
                    return null;
                }
                cur = cur instanceof UiValue.Arr a && index >= 0 && index < a.items().size() ? a.items().get(index) : null;
                i = end + 1;
                continue;
            }
            int end = i;
            while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
                end++;
            }
            cur = cur instanceof UiValue.Obj o ? o.get(path.substring(i, end)) : null;
            i = end;
        }
        return cur;
    }
}
