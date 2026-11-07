package com.stonebreak.world.generation.diffusion.tgmpipe;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class or method that needs the real {@value TGMPipe#MODEL_NAME}. On a machine below
 * {@link DaedalusRequirements} it is skipped, with the reason, before any {@code @BeforeAll} runs, so
 * nothing starts the service or waits on the experimental CPU kernels.
 *
 * <p>Meeting the requirements does not promise the model is installed: tests still skip on their own
 * when the service cannot start (no venv, no checkpoints).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresDaedalus.Condition.class)
public @interface RequiresDaedalus {

    final class Condition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            DaedalusRequirements.Verdict v = DaedalusRequirements.check();
            return v.met()
                    ? ConditionEvaluationResult.enabled(TGMPipe.MODEL_NAME + " system requirements met")
                    : ConditionEvaluationResult.disabled(DaedalusRequirements.disabledMessage(v));
        }
    }
}
