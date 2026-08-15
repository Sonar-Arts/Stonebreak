package com.stonebreak.ui.chat.chatSystem;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the character input contract of {@link ChatInputHandler}: only printable
 * ASCII (32..126) is accepted, input stops growing at 256 characters, and
 * backspace is safe on empty input.
 *
 * <p>Deliberately does NOT test {@code handlePaste()}, {@code handleCopy()},
 * {@code copyToClipboard(...)}, or {@code handleTab(...)} — those reach
 * {@code Game.getInstance().getWindow()} and GLFW clipboard functions.
 *
 * <p>Regression: a change to {@code handleCharInput} that accepts non-printable
 * characters or allows the buffer to exceed 256 chars would corrupt chat commands
 * or cause buffer overflows.
 */
class ChatInputHandlerTest {

    private ChatInputHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ChatInputHandler();
    }

    // ---- printable ASCII characters are accepted ----------------------------------------------

    @Test
    void printableAsciiLettersAreAccepted() {
        handler.handleCharInput('A');
        handler.handleCharInput('z');
        assertEquals("Az", handler.getCurrentInput(),
            "printable ASCII letters must be accepted");
    }

    @Test
    void printableAsciiDigitsAreAccepted() {
        handler.handleCharInput('0');
        handler.handleCharInput('9');
        assertEquals("09", handler.getCurrentInput(),
            "printable ASCII digits must be accepted");
    }

    @Test
    void printableSpaceIsAccepted() {
        handler.handleCharInput(' ');
        assertEquals(" ", handler.getCurrentInput(),
            "space (char 32) must be accepted as a valid input character");
    }

    @Test
    void printablePunctuationIsAccepted() {
        handler.handleCharInput('@');  // 64
        handler.handleCharInput('~');  // 126
        assertEquals("@~", handler.getCurrentInput(),
            "printable punctuation characters must be accepted");
    }

    // ---- non-printable characters are rejected ------------------------------------------------

    @Test
    void nullCharIsRejected() {
        handler.handleCharInput('\0');
        assertEquals("", handler.getCurrentInput(),
            "null character (char 0) must be rejected");
    }

    @Test
    void newlineCharIsRejected() {
        handler.handleCharInput('\n');
        assertEquals("", handler.getCurrentInput(),
            "newline character (char 10) must be rejected");
    }

    @Test
    void tabCharIsRejected() {
        handler.handleCharInput('\t');
        assertEquals("", handler.getCurrentInput(),
            "tab character (char 9) must be rejected");
    }

    @Test
    void char31IsRejected() {
        handler.handleCharInput((char) 31);
        assertEquals("", handler.getCurrentInput(),
            "char 31 (just below space) must be rejected");
    }

    @Test
    void char127IsRejected() {
        handler.handleCharInput((char) 127);
        assertEquals("", handler.getCurrentInput(),
            "char 127 (just above ~) must be rejected");
    }

    // ---- input stops growing at 256 characters ------------------------------------------------

    @Test
    void inputStopsGrowingAt256Characters() {
        for (int i = 0; i < 300; i++) {
            handler.handleCharInput('A');
        }
        assertEquals(256, handler.getCurrentInput().length(),
            "input must stop growing at MAX_INPUT_LENGTH (256)");
    }

    @Test
    void char257IsRejectedAfter256() {
        for (int i = 0; i < 256; i++) {
            handler.handleCharInput('A');
        }
        handler.handleCharInput('B');
        assertEquals(256, handler.getCurrentInput().length(),
            "the 257th character must be rejected");
        // Verify only 'A's remain
        for (int i = 0; i < 256; i++) {
            assertEquals('A', handler.getCurrentInput().charAt(i),
                "only the first 256 'A' characters must be present");
        }
    }

    // ---- backspace on empty input is safe -----------------------------------------------------

    @Test
    void backspaceOnEmptyInputIsSafe() {
        handler.handleBackspace();
        assertEquals("", handler.getCurrentInput(),
            "backspace on empty input must be safe and leave input empty");
    }

    @Test
    void repeatedBackspaceOnEmptyInputIsSafe() {
        handler.handleBackspace();
        handler.handleBackspace();
        handler.handleBackspace();
        assertEquals("", handler.getCurrentInput(),
            "multiple backspaces on empty input must be safe");
    }

    // ---- single printable char round-trips ----------------------------------------------------

    @Test
    void singlePrintableCharRoundTrips() {
        handler.handleCharInput('X');
        assertEquals("X", handler.getCurrentInput(),
            "handleCharInput('X') must produce input \"X\"");
    }

    @Test
    void charInputThenBackspaceReturnsToEmpty() {
        handler.handleCharInput('X');
        handler.handleBackspace();
        assertEquals("", handler.getCurrentInput(),
            "after handleCharInput('X') and handleBackspace, input must be empty");
    }

    // ---- multiple backspaces on a 5-char input ------------------------------------------------

    @Test
    void multipleBackspacesEventuallyEmptyTheBuffer() {
        handler.handleCharInput('H');
        handler.handleCharInput('i');
        assertEquals("Hi", handler.getCurrentInput());

        handler.handleBackspace();
        assertEquals("H", handler.getCurrentInput());
        handler.handleBackspace();
        assertEquals("", handler.getCurrentInput(),
            "two backspaces on 2-char input must empty the buffer");
    }

    // ---- insertToken appends token to current input -------------------------------------------

    @Test
    void insertTokenAppendsToCurrentInput() {
        handler.handleCharInput('A');
        handler.insertToken("[tag]");
        assertEquals("A[tag]", handler.getCurrentInput(),
            "insertToken must append the token to existing input");
    }

    @Test
    void insertTokenOnEmptyInput() {
        handler.insertToken("[item]");
        assertEquals("[item]", handler.getCurrentInput(),
            "insertToken on empty input must set the token as the input");
    }

    // ---- insertToken respects MAX_INPUT_LENGTH ------------------------------------------------

    @Test
    void insertTokenRespectsMaxLength() {
        // Fill to 250 chars
        for (int i = 0; i < 250; i++) {
            handler.handleCharInput('A');
        }
        // Insert a 20-char token — only 6 should fit
        handler.insertToken("12345678901234567890");
        assertEquals(256, handler.getCurrentInput().length(),
            "insertToken must respect MAX_INPUT_LENGTH and truncate the token");
    }

    // ---- clear resets input to empty ----------------------------------------------------------

    @Test
    void clearResetsInputToEmpty() {
        handler.handleCharInput('H');
        handler.handleCharInput('i');
        handler.clear();
        assertEquals("", handler.getCurrentInput(),
            "clear() must reset input to empty string");
    }
}