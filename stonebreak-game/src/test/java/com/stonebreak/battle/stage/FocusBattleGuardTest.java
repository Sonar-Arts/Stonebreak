package com.stonebreak.battle.stage;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HUD guards. Regression: the boolean guard used to be an OVERLOAD of guardHud, and its own
 * body {@code guardHud(what, () -> handled[0] = call.getAsBoolean())} is an expression lambda that
 * returns a value, so it bound to the BooleanSupplier overload, i.e. to itself, and every key press
 * or click in a battle died with a StackOverflowError.
 */
class FocusBattleGuardTest {

    @Test void theInputGuardReturnsWhatTheHandlerReturnedExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        assertTrue(FocusBattle.guardHudInput("key", () -> { calls.incrementAndGet(); return true; }));
        assertFalse(FocusBattle.guardHudInput("key", () -> { calls.incrementAndGet(); return false; }));
        assertEquals(2, calls.get(), "the handler runs once per event, never recursively");
    }

    @Test void aThrowingHandlerIsContainedAndReportsNotHandled() {
        assertFalse(FocusBattle.guardHudInput("key", () -> { throw new IllegalStateException("boom"); }));
        assertDoesNotThrow(() -> FocusBattle.guardHud("render", () -> { throw new IllegalStateException("boom"); }));
    }

    @Test void aBooleanReturningExpressionLambdaIsJustARunnableToTheRenderGuard() {
        // The exact call shape the mouse router uses: handleMouseClick(...) returns boolean.
        AtomicInteger calls = new AtomicInteger();
        FocusBattle.guardHud("click", () -> click(calls));
        assertEquals(1, calls.get());
    }

    private static boolean click(AtomicInteger calls) {
        return calls.incrementAndGet() > 0;
    }
}
