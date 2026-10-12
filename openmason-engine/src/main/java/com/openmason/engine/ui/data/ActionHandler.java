package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;

import java.util.concurrent.CompletionStage;

/**
 * Host implementation of one action (#289). Called on the UI thread with arguments that
 * already passed the spec's parameter schema; it must not block. Quick work completes the
 * returned stage before returning; slow work completes it later from any thread (the result
 * is marshalled back to the UI thread and checked against the result schema there). Throwing
 * or completing exceptionally fails the call.
 *
 * <p>The handler is where the game validates its rules and authority, exactly as the legacy
 * screen code did; the UI never bypasses it.
 */
@FunctionalInterface
public interface ActionHandler {

    CompletionStage<UiValue> handle(UiValue.Obj args, ActionContext context);
}
