package com.stonebreak.ui.chat.chatSystem;

import com.openmason.engine.ui.text.TextInputRules;

/**
 * The chat line's text rules as an engine {@link TextInputRules} (#288), so a migrated chat
 * {@code TextField} keeps today's behaviour exactly: printable ASCII only (emoji travel as
 * {@code [name]} tokens from the picker), at most {@value #MAX_LENGTH} characters, one line.
 * {@code ChatTextRulesTest} pins the equivalence with {@link ChatInputHandler}.
 */
public final class ChatTextRules {

    public static final int MAX_LENGTH = 256;

    public static final TextInputRules RULES = TextInputRules.singleLine()
        .withAllowed(TextInputRules.ASCII_PRINTABLE)
        .withMaxLength(MAX_LENGTH);

    private ChatTextRules() {
    }
}
