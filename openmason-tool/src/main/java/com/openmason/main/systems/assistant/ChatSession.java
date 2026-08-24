package com.openmason.main.systems.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered message list + revision counter. The worker mutates, the UI takes
 * cheap snapshots; both synchronize here. Holds the model id the session is
 * pinned to (per-turn probes may adopt a new one explicitly).
 */
public final class ChatSession {

    private final List<ChatMessage> messages = new ArrayList<>();
    private long revision;
    private String modelId;
    private long promptTokens;
    private long completionTokens;

    public synchronized void add(ChatMessage message) {
        messages.add(message);
        revision++;
    }

    public synchronized void touch() {
        revision++;
    }

    public synchronized List<ChatMessage> snapshot() {
        return List.copyOf(messages);
    }

    public synchronized void clear() {
        messages.clear();
        promptTokens = 0;
        completionTokens = 0;
        revision++;
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized String modelId() {
        return modelId;
    }

    public synchronized void setModelId(String modelId) {
        this.modelId = modelId;
        revision++;
    }

    public synchronized void recordUsage(long prompt, long completion) {
        this.promptTokens = prompt;
        this.completionTokens += completion;
        revision++;
    }

    public synchronized long promptTokens() {
        return promptTokens;
    }

    public synchronized long completionTokens() {
        return completionTokens;
    }
}
