package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.UiDependency.Kind;

import java.util.List;

/**
 * File conventions per dependency kind. One layout is used everywhere a shared asset lives by
 * convention — project UI folder, packaged game resources, resource packs and archive
 * {@code assets/}: {@code <namespace>/<path><extension>}, so {@code stonebreak:ui/textures/panel}
 * is {@code stonebreak/ui/textures/panel.sbt}. Extensions are tried in the listed order, which
 * makes convention lookup deterministic when two files would match.
 */
public final class AssetKinds {

    private AssetKinds() {
    }

    /** Accepted file extensions for {@code kind}, preferred first. */
    public static List<String> extensions(Kind kind) {
        return switch (kind) {
            case TEXTURE -> List.of(".sbt", ".omt");
            case SPRITES -> List.of(".sprites.json");
            case IMAGE -> List.of(".png");
            case COMPONENT -> List.of(".omui");
            case STYLESHEET -> List.of(".uss.json");
            case SCRIPT -> List.of(".lua");
            case FONT -> List.of(".ttf", ".otf");
            case SOUND -> List.of(".ogg", ".wav");
        };
    }

    /** {@code stonebreak:ui/panel} → {@code stonebreak/ui/panel}. Safe because logical ids never contain dots-only segments. */
    public static String idPath(String id) {
        return id.replace(':', '/');
    }

    /**
     * Extension for a file of {@code kind}: the kind extension {@code hint} ends with, else
     * the hint's last {@code .ext}, else the kind's preferred extension.
     */
    public static String extension(Kind kind, String hint) {
        if (hint != null) {
            for (String ext : extensions(kind)) {
                if (hint.endsWith(ext)) {
                    return ext;
                }
            }
            String file = hint.substring(hint.lastIndexOf('/') + 1);
            int dot = file.lastIndexOf('.');
            if (dot > 0) {
                return file.substring(dot);
            }
        }
        return extensions(kind).getFirst();
    }

    /** Convention-relative file for {@code id}: {@code <namespace>/<path><extension>}. */
    public static String fileName(String id, Kind kind, String hint) {
        return idPath(id) + extension(kind, hint);
    }

    /** Every convention-relative candidate for {@code id}, in lookup order. */
    public static List<String> candidates(String id, Kind kind) {
        String base = idPath(id);
        return extensions(kind).stream().map(ext -> base + ext).toList();
    }
}
