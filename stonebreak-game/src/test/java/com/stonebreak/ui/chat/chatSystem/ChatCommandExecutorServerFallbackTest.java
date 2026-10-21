package com.stonebreak.ui.chat.chatSystem;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR #410 review (issue #334): a server-executed command with no client to forward it to (the
 * battle test, menus) runs its local fallback, which says so — it used to vanish silently into a
 * no-op submitChat.
 */
@Tag("regression")
class ChatCommandExecutorServerFallbackTest {

    @Test
    void obtainWithoutAServerSessionReportsItInsteadOfVanishing() {
        ChatMessageManager messages = new ChatMessageManager(new TextWrapper());
        ChatCommandExecutor executor = new ChatCommandExecutor(messages);
        executor.setServerSessionCheck(() -> false);

        executor.executeCommand("/obtain stone 3");

        assertTrue(messages.getVisibleMessages(true).stream()
                .anyMatch(m -> m.getText().contains("No server session")),
            "the fallback message is shown");
    }
}
