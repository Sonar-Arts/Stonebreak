package com.stonebreak.ui.chat.chatSystem;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the cursor blink timing contract of {@link ChatCursorState}: {@code reset()}
 * forces the cursor visible, stepping {@code update} past the 1.0s interval toggles
 * visibility, and {@code getDisplayCursor()} is always consistent with
 * {@code shouldShowCursor()}.
 *
 * <p>Regression: a change to the blink interval math that loses the reset-to-visible
 * after {@code reset()}, or a toggle that doesn't symmetrise over two intervals,
 * would produce a cursor that gets stuck in one state.
 */
class ChatCursorStateTest {

    private ChatCursorState cursor;

    @BeforeEach
    void setUp() {
        cursor = new ChatCursorState();
    }

    // ---- initial state ------------------------------------------------------------------------

    @Test
    void initialCursorIsVisible() {
        assertTrue(cursor.shouldShowCursor(),
            "cursor must be visible immediately after construction");
        assertEquals("_", cursor.getDisplayCursor(),
            "initial getDisplayCursor must return \"_\"");
    }

    // ---- reset() forces cursor visible --------------------------------------------------------

    @Test
    void resetForcesCursorVisible() {
        // First toggle it hidden
        cursor.update(1.0f);
        assertFalse(cursor.shouldShowCursor());

        cursor.reset();
        assertTrue(cursor.shouldShowCursor(),
            "reset() must force cursor visible regardless of previous state");
        assertEquals("_", cursor.getDisplayCursor(),
            "reset() must make getDisplayCursor return \"_\"");
    }

    // ---- stepping past 1.0s toggles visibility ------------------------------------------------

    @Test
    void updatePastIntervalTogglesVisibility() {
        cursor.update(1.0f);
        assertFalse(cursor.shouldShowCursor(),
            "stepping exactly 1.0s must toggle cursor to hidden");
        assertEquals("", cursor.getDisplayCursor(),
            "hidden cursor must return empty string from getDisplayCursor");
    }

    // ---- stepping past 2.0s returns to original state -----------------------------------------

    @Test
    void oneUpdateTogglesAtMostOnceNoMatterHowLargeTheDelta() {
        // update() toggles once and zeroes the timer — it does not consume whole intervals in a
        // loop. So a single huge delta (a stalled frame, a debugger pause) blinks the cursor once
        // rather than flickering it N times. Worth pinning: the obvious "while" refactor would
        // silently change this.
        cursor.update(2.0f);
        assertFalse(cursor.shouldShowCursor(),
            "a single 2.0s step must toggle exactly once, leaving the cursor hidden");

        cursor.update(2.0f);
        assertTrue(cursor.shouldShowCursor(),
            "a second large step toggles once more, returning the cursor to visible");
    }

    // ---- multiple small steps summing to exactly 1.0 toggle -----------------------------------

    @Test
    void smallStepsSummingToOneSecondToggle() {
        cursor.update(0.25f);
        cursor.update(0.25f);
        cursor.update(0.25f);
        cursor.update(0.25f);

        assertFalse(cursor.shouldShowCursor(),
            "four 0.25s updates (total 1.0s) must toggle cursor to hidden");
    }

    // ---- multiple small steps summing to exactly 2.0 return to original -----------------------

    @Test
    void smallStepsSummingToTwoSecondsReturnToOriginal() {
        for (int i = 0; i < 20; i++) {
            cursor.update(0.1f); // 20 * 0.1 = 2.0s
        }
        assertTrue(cursor.shouldShowCursor(),
            "20 updates of 0.1s (total 2.0s) must return cursor to visible");
    }

    // ---- getDisplayCursor is consistent with shouldShowCursor ---------------------------------

    @Test
    void getDisplayCursorIsConsistentWithShouldShowCursorOnEveryStep() {
        for (int i = 0; i < 10; i++) {
            cursor.update(0.5f);

            if (cursor.shouldShowCursor()) {
                assertEquals("_", cursor.getDisplayCursor(),
                    "step " + i + ": getDisplayCursor must be \"_\" when shouldShowCursor is true");
            } else {
                assertEquals("", cursor.getDisplayCursor(),
                    "step " + i + ": getDisplayCursor must be \"\" when shouldShowCursor is false");
            }
        }
    }

    // ---- update with 0 deltaTime does not change state ----------------------------------------

    @Test
    void updateWithZeroDeltaTimeDoesNotChangeState() {
        cursor.update(0.0f);
        assertTrue(cursor.shouldShowCursor(),
            "update(0.0) must not change cursor state");

        // Even after a toggle, 0 deltaTime should not re-toggle
        cursor.update(1.0f);
        assertFalse(cursor.shouldShowCursor());
        cursor.update(0.0f);
        assertFalse(cursor.shouldShowCursor(),
            "update(0.0) after a toggle must not re-toggle");
    }

    // ---- large deltaTime produces consistent toggle (even intervals = same state) -------------

    @Test
    void aVeryLargeDeltaStillOnlyTogglesOnce() {
        cursor.update(10.0f);
        assertFalse(cursor.shouldShowCursor(),
            "10.0s in one step must still be a single toggle, not ten");
    }

    @Test
    void timerIsZeroedAfterAToggleSoTheNextBlinkTakesAFullInterval() {
        cursor.update(3.0f); // toggles once, timer resets to 0 (the 2s surplus is discarded)
        assertFalse(cursor.shouldShowCursor(), "the 3.0s step toggles the cursor hidden");

        // If the surplus had carried over, this sub-interval step would toggle again.
        cursor.update(0.5f);
        assertFalse(cursor.shouldShowCursor(),
            "surplus time is discarded on toggle, so half an interval must not blink again");

        cursor.update(0.5f); // now a full interval has elapsed since the toggle
        assertTrue(cursor.shouldShowCursor(), "a full interval after the toggle blinks again");
    }

    // ---- sub-interval updates don't toggle ----------------------------------------------------

    @Test
    void subIntervalUpdateDoesNotToggle() {
        cursor.update(0.999f);
        assertTrue(cursor.shouldShowCursor(),
            "0.999s (< 1.0s interval) must not toggle cursor");
    }
}