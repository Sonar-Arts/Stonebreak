package com.openmason.main.systems.mcp;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Teaching-error factories for MCP tools.
 *
 * <p>Every message tells the caller what to do instead: known names, valid
 * ranges, the tool to call for discovery, and a did-you-mean suggestion where
 * one is close. The router surfaces these as {@code isError:true} results.
 */
public final class McpErrors {

    /** Cap on how many known names are listed in an error message. */
    private static final int MAX_LISTED = 15;

    private McpErrors() {
    }

    /**
     * Unknown entity (part/bone/socket/...) referenced by id or name.
     *
     * @param kind     entity kind for the message, e.g. "part"
     * @param given    what the caller passed
     * @param known    the known display names (may be empty)
     * @param listTool tool that lists them, e.g. "list_parts"
     */
    public static IllegalArgumentException unknownEntity(
            String kind, String given, Collection<String> known, String listTool) {
        StringBuilder sb = new StringBuilder("No ").append(kind).append(" '").append(given).append("'.");
        if (known == null || known.isEmpty()) {
            sb.append(" None exist yet.");
        } else {
            String suggestion = closest(given, known);
            if (suggestion != null) {
                sb.append(" Did you mean '").append(suggestion).append("'?");
            }
            sb.append(" Known ").append(kind).append("s: ").append(listNames(known)).append('.');
        }
        sb.append(" (call ").append(listTool).append("; names are case-sensitive)");
        return new IllegalArgumentException(sb.toString());
    }

    /**
     * Index-style argument outside the valid range.
     *
     * @param what     argument name, e.g. "local_face_id"
     * @param given    the out-of-range value
     * @param min      inclusive minimum
     * @param max      inclusive maximum
     * @param context  what owns the range, e.g. "part 'body'"
     * @param listTool tool that enumerates valid values
     */
    public static IllegalArgumentException outOfRange(
            String what, long given, long min, long max, String context, String listTool) {
        return new IllegalArgumentException(
                what + " " + given + " out of range; " + context + " has " + what + "s "
                        + min + ".." + max + " (call " + listTool + ")");
    }

    /**
     * Invalid enum-style string argument, with a did-you-mean suggestion.
     *
     * @param what  argument name, e.g. "shape"
     * @param given what the caller passed
     * @param valid the valid values
     */
    public static IllegalArgumentException invalidEnum(
            String what, String given, Collection<String> valid) {
        StringBuilder sb = new StringBuilder("Unknown ").append(what).append(" '").append(given).append("'.");
        String suggestion = closest(given, valid);
        if (suggestion != null) {
            sb.append(" Did you mean '").append(suggestion).append("'?");
        }
        sb.append(" Valid: ").append(listNames(valid)).append('.');
        return new IllegalArgumentException(sb.toString());
    }

    private static String listNames(Collection<String> names) {
        String joined = names.stream().limit(MAX_LISTED).collect(Collectors.joining(", "));
        return names.size() > MAX_LISTED ? joined + ", … (" + names.size() + " total)" : joined;
    }

    /** Nearest candidate by edit distance, case-insensitive; null when nothing is close. */
    static String closest(String given, Collection<String> candidates) {
        if (given == null || candidates == null) return null;
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = levenshtein(given.toLowerCase(), c.toLowerCase());
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        // Only suggest when plausibly a typo: distance small relative to length.
        int threshold = Math.max(1, given.length() / 3);
        return (best != null && bestDist > 0 && bestDist <= threshold) ? best : null;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
