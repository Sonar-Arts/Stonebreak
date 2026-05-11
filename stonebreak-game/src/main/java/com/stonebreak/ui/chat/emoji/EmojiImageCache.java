package com.stonebreak.ui.chat.emoji;

import io.github.humbleui.skija.Image;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

public final class EmojiImageCache {

    private static final Map<EmojiType, Image> CACHE = new EnumMap<>(EmojiType.class);

    private EmojiImageCache() {}

    public static Image get(EmojiType type) {
        return CACHE.computeIfAbsent(type, t -> {
            try (InputStream in = EmojiImageCache.class.getResourceAsStream(t.resourcePath)) {
                if (in == null) {
                    System.err.println("[EmojiImageCache] Missing resource: " + t.resourcePath);
                    return null;
                }
                return Image.makeFromEncoded(in.readAllBytes());
            } catch (IOException e) {
                System.err.println("[EmojiImageCache] Failed to load " + t.resourcePath + ": " + e.getMessage());
                return null;
            }
        });
    }
}
