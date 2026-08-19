package com.stonebreak.ui.chat.emoji;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat emoji catalog, same contract as the class-icon registry: every declared id and token
 * must be unique across BOTH the static and animated enums (tokens are what chat text carries,
 * so a collision would render the wrong emoji), and every resource path must resolve on the game
 * classpath — a typo'd path shows as a broken image in chat and nothing else reports it.
 */
class EmojiAssetsTest {

    private static List<ChatEmoji> allEmoji() {
        List<ChatEmoji> all = new ArrayList<>();
        all.addAll(List.of(EmojiType.values()));
        all.addAll(List.of(GifEmojiType.values()));
        return all;
    }

    @Test
    void idsAndTokensAreUniqueAcrossStaticAndAnimatedEmoji() {
        Set<String> ids = new HashSet<>();
        Set<String> tokens = new HashSet<>();

        for (ChatEmoji emoji : allEmoji()) {
            assertTrue(ids.add(emoji.getId()), "duplicate emoji id: " + emoji.getId());
            assertTrue(tokens.add(emoji.getToken()), "duplicate chat token: " + emoji.getToken());
        }
    }

    @Test
    void tokensAreTheBracketedIdChatTextExpects() {
        for (ChatEmoji emoji : allEmoji()) {
            assertTrue(emoji.getToken().equals("[" + emoji.getId() + "]"),
                    emoji.getId() + " token drifted from the [id] form: " + emoji.getToken());
        }
    }

    @Test
    void everyEmojiImageResolvesOnTheGameClasspath() {
        for (EmojiType emoji : EmojiType.values()) {
            assertResourceExists(emoji.resourcePath, emoji.getId());
        }
        for (GifEmojiType emoji : GifEmojiType.values()) {
            assertResourceExists(emoji.resourcePath, emoji.getId());
        }
    }

    @Test
    void animationFlagsMatchTheEnumTheyLiveIn() {
        for (EmojiType emoji : EmojiType.values()) {
            assertTrue(!emoji.isAnimated(), emoji.getId() + " is a static PNG");
        }
        for (GifEmojiType emoji : GifEmojiType.values()) {
            assertTrue(emoji.isAnimated(), emoji.getId() + " is an animated GIF");
        }
    }

    private static void assertResourceExists(String path, String id) {
        try (InputStream in = EmojiAssetsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "emoji '" + id + "' points at a missing resource: " + path);
        } catch (Exception e) {
            throw new AssertionError("emoji '" + id + "' could not be opened: " + path, e);
        }
    }
}
