package com.openmason.engine.format.omui;

import java.util.Map;

/**
 * Built-in widget types schema 1.0 knows and the newest descriptor version of each. Property
 * descriptors and their validation belong to #287; the format only checks that a node's type
 * exists and that its {@code typeVersion} is not newer than this reader.
 *
 * <p>The version-one set is what the pause and furnace pilots need (#283 contracts), plus
 * {@code Instance} for component instances. Namespaced types ({@code stonebreak:CrucibleView})
 * are host providers and must be declared in the manifest's {@code providers}.
 */
public final class UiWidgets {

    private static final Map<String, Integer> BUILT_IN = Map.of(
            "Box", 1,
            "Label", 1,
            "Button", 1,
            "Image", 1,
            "ItemSlot", 1,
            "DrawProvider", 1,
            "ScrollView", 1,
            UiNode.INSTANCE_TYPE, 1);

    /** Built-ins newer than schema 1.0 and the {@code requires} feature a document must list to use them. */
    private static final Map<String, String> FEATURE = Map.of("ScrollView", UiFeatures.SCROLL);

    private UiWidgets() {
    }

    /** @return the newest supported version, or 0 for an unknown built-in type */
    public static int supportedVersion(String type) {
        return BUILT_IN.getOrDefault(type, 0);
    }

    /** @return the feature a document must declare in {@code requires} to use {@code type}, or null */
    public static String requiredFeature(String type) {
        return FEATURE.get(type);
    }

    public static boolean isNamespaced(String type) {
        return type.indexOf(':') >= 0;
    }
}
