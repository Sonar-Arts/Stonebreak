package com.openmason.engine.format.omui;

import java.util.regex.Pattern;

/**
 * Syntax of data paths and property targets shared by bindings, clips and overrides.
 *
 * <ul>
 *   <li>Data path: {@code segment ( "." segment | "[" index "]" )*}, optionally prefixed with
 *       {@code .} to resolve against the inherited data source. Segments are identifiers.</li>
 *   <li>Property target: {@code prop:<name>}, {@code style:<property>} or {@code class:<name>}.</li>
 *   <li>Override target: node id path through nested instances, {@code inst/leaf}.</li>
 * </ul>
 */
public final class UiPaths {

    private static final Pattern DATA_PATH = Pattern.compile(
            "\\.?[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*|\\[(0|[1-9][0-9]{0,8})])*");
    private static final Pattern OVERRIDE_TARGET = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_-]{0,63}(/[A-Za-z_][A-Za-z0-9_-]{0,63}){0,15}");

    private UiPaths() {
    }

    public static boolean isDataPath(String path) {
        return path != null && path.length() <= 512 && (path.equals(".") || DATA_PATH.matcher(path).matches());
    }

    public static boolean isOverrideTarget(String target) {
        return target != null && OVERRIDE_TARGET.matcher(target).matches();
    }

    /** @return {@code null} when {@code target} is a valid property target, else the problem */
    public static String targetProblem(String target) {
        int colon = target.indexOf(':');
        if (colon < 0) {
            return "expected prop:, style: or class: prefix";
        }
        String kind = target.substring(0, colon);
        String name = target.substring(colon + 1);
        return switch (kind) {
            case "prop" -> UiSelectors.isIdent(name) ? null : "invalid property name '" + name + "'";
            case "class" -> UiSelectors.isIdent(name) ? null : "invalid class name '" + name + "'";
            case "style" -> UiSelectors.isIdent(name) ? null : "invalid style property name '" + name + "'";
            default -> "unknown target kind '" + kind + "'";
        };
    }
}
