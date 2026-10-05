package com.stonebreak.ui.chat.chatSystem;

import com.openmason.engine.ui.text.TextEditModel;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Chat keeps its text and emoji behaviour through migration (#288): the engine text model with
 * {@link ChatTextRules#RULES} accepts exactly what {@link ChatInputHandler} accepts, for typing,
 * backspace, emoji tokens and pasted text, including the 256-character limit.
 */
class ChatTextRulesTest {

    private static final String[] PASTES = {"hello world", "é ünïcode", "tab\there", "line\nbreak", "👍 thumbs",
        "x".repeat(300)};
    private static final String[] TOKENS = {"[banana]", "[party_parrot]", "[" + "z".repeat(40) + "]"};

    @Test
    void randomSessionsMatchTheLegacyHandler() {
        Random rnd = new Random(288);
        for (int session = 0; session < 200; session++) {
            ChatInputHandler legacy = new ChatInputHandler();
            TextEditModel model = new TextEditModel(ChatTextRules.RULES);
            for (int op = 0; op < 120; op++) {
                switch (rnd.nextInt(6)) {
                    case 0, 1 -> {
                        // the BMP characters the legacy path receives (it drops anything else earlier)
                        char c = (char) (rnd.nextBoolean() ? 32 + rnd.nextInt(95) : rnd.nextInt(0x3000));
                        legacy.handleCharInput(c);
                        if (!Character.isSurrogate(c)) {
                            model.insert(String.valueOf(c));
                        }
                    }
                    case 2 -> {
                        legacy.handleBackspace();
                        model.backspace(false);
                    }
                    case 3 -> {
                        String token = TOKENS[rnd.nextInt(TOKENS.length)];
                        legacy.insertToken(token);
                        model.insert(token);
                    }
                    case 4 -> {
                        String paste = PASTES[rnd.nextInt(PASTES.length)];
                        legacy.setInput(legacy.getCurrentInput() + paste);
                        model.insert(paste);
                    }
                    default -> {
                        legacy.clear();
                        model.setText("");
                    }
                }
                assertEquals(legacy.getCurrentInput(), model.text(), "session " + session + " op " + op);
            }
        }
    }

    @Test
    void theLimitAndFilterAreTheLegacyOnes() {
        TextEditModel model = new TextEditModel(ChatTextRules.RULES);
        model.insert("a".repeat(300));
        assertEquals(256, model.text().length());
        model.setText("");
        model.insert("café 👍!");
        assertEquals("caf !", model.text(), "chat stays printable ASCII; emoji travel as [tokens]");
    }
}
