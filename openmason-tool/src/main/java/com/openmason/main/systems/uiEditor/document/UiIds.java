package com.openmason.main.systems.uiEditor.document;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Node identity for the editor. Ids are document-local and stable: create, duplicate and paste
 * mint fresh ones; rename and reparent never touch them, so every reference (clips, graphs,
 * overrides, editor metadata) keeps resolving. Names ({@code #name}) are only style handles and
 * are kept unique too, so a duplicate never silently matches its original's {@code #name} rules.
 */
public final class UiIds {

    private static final Pattern NUMBERED = Pattern.compile("(.*?)_(\\d+)$");

    private UiIds() {
    }

    /** A valid id derived from {@code base} ({@code Button} → {@code button}) not in {@code taken}. */
    public static String fresh(String base, Set<String> taken) {
        String stem = sanitize(base);
        Matcher m = NUMBERED.matcher(stem);
        int n = 1;
        if (m.matches()) {
            stem = m.group(1);
            n = Integer.parseInt(m.group(2));
        }
        if (!taken.contains(stem) && n == 1) {
            return stem;
        }
        for (int i = Math.max(2, n); ; i++) {
            String candidate = trim(stem, ("_" + i).length()) + "_" + i;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** {@code ScrollView} → {@code scroll_view}; invalid characters dropped; never empty. */
    public static String sanitize(String base) {
        String type = base == null ? "" : base;
        int colon = type.indexOf(':');
        if (colon >= 0) {
            type = type.substring(colon + 1);
        }
        int slash = type.lastIndexOf('/');
        if (slash >= 0) {
            type = type.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(type.charAt(i - 1))) {
                sb.append('_');
            }
            if (Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '-') {
                sb.append(Character.toLowerCase(c));
            } else if (c == ' ' || c == '.') {
                sb.append('_');
            }
        }
        String s = sb.toString();
        if (s.isEmpty() || !(Character.isLetter(s.charAt(0)) || s.charAt(0) == '_')) {
            s = "n_" + s;
        }
        s = trim(s, 0);
        return OmuiFormat.LOCAL_ID.matcher(s).matches() ? s : "node";
    }

    private static String trim(String s, int reserve) {
        int max = 64 - reserve;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * A copy of {@code node}'s subtree (children and slot content) with fresh ids, and fresh
     * names wherever a name is set. {@code takenIds}/{@code takenNames} are extended with what it
     * mints, so several copies pasted at once stay unique among themselves.
     *
     * @param idMap receives old id → new id for every copied node
     */
    public static UiNode remap(UiNode node, Set<String> takenIds, Set<String> takenNames, Map<String, String> idMap) {
        String id = fresh(node.id(), takenIds);
        takenIds.add(id);
        idMap.put(node.id(), id);
        UiNode copy = Nodes.withId(node, id);
        if (node.name() != null) {
            String name = fresh(node.name(), takenNames);
            takenNames.add(name);
            copy = Nodes.withName(copy, name);
        }
        List<UiNode> kids = new ArrayList<>();
        for (UiNode c : node.children()) {
            kids.add(remap(c, takenIds, takenNames, idMap));
        }
        copy = copy.withChildren(kids);
        if (node.instance() != null && !node.instance().slots().isEmpty()) {
            Map<String, List<UiNode>> slots = new LinkedHashMap<>();
            node.instance().slots().forEach((slot, list) -> {
                List<UiNode> mapped = new ArrayList<>();
                list.forEach(c -> mapped.add(remap(c, takenIds, takenNames, idMap)));
                slots.put(slot, mapped);
            });
            UiNode.ComponentInstance inst = copy.instance();
            copy = Nodes.withInstance(copy, new UiNode.ComponentInstance(inst.component(), inst.params(),
                inst.overrides(), slots, inst.unknown()));
        }
        return copy;
    }

    /** Lower-case display stem for a widget or component id ({@code stone_button}). */
    public static String stem(String typeOrComponent) {
        return sanitize(typeOrComponent).toLowerCase(Locale.ROOT);
    }
}
