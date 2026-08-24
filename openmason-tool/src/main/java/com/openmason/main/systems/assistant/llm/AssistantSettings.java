package com.openmason.main.systems.assistant.llm;

/**
 * Immutable snapshot of assistant configuration, read from preferences at
 * send time so mid-session changes apply to the next turn.
 *
 * @param contextTokensOverride 0 = use the model table
 */
public record AssistantSettings(String endpoint, String apiKey, float temperature,
                                int maxToolIterations, long contextTokensOverride,
                                String promptExtras, ApprovalPolicy readOnlyPolicy,
                                ApprovalPolicy mutatingPolicy, ApprovalPolicy requiresAuthPolicy,
                                boolean autoCompact) {

    /** Per-category approval behavior. */
    public enum ApprovalPolicy {
        AUTO, ASK, DENY;

        public static ApprovalPolicy fromString(String s, ApprovalPolicy fallback) {
            if (s == null) {
                return fallback;
            }
            try {
                return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public static AssistantSettings defaults() {
        return new AssistantSettings("http://localhost:8001/v1", "local-dev-key", 0.7f, 25, 0,
                "", ApprovalPolicy.AUTO, ApprovalPolicy.AUTO, ApprovalPolicy.ASK, true);
    }
}
