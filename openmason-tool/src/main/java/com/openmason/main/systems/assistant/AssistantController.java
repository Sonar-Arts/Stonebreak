package com.openmason.main.systems.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.main.systems.assistant.llm.AssistantSettings;
import com.openmason.main.systems.assistant.llm.LlmClient;
import com.openmason.main.systems.assistant.llm.ModelInfo;
import com.openmason.main.systems.assistant.llm.SseParser;
import com.openmason.main.systems.assistant.llm.StreamDelta;
import com.openmason.main.systems.assistant.tools.ToolBridge;
import com.openmason.main.systems.assistant.tools.ToolCategory;
import com.openmason.main.systems.mcp.McpToolRegistry;
import com.openmason.main.systems.scripting.python.PythonScriptEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The in-tool agentic loop: user text → local LLM (OpenAI function calling)
 * → serialized tool dispatch through the shared {@link McpToolRegistry}
 * handlers → results fed back — until the model stops, the iteration budget
 * runs out, or the user cancels.
 *
 * <p>One daemon worker thread owns all HTTP + tool dispatch (tool handlers
 * hop to the main thread internally, exactly like external MCP clients).
 * UI-thread API: {@link #send}, {@link #cancel}, {@link #newChat},
 * {@link #resolveApproval}, {@link #probeAsync}, and read-only getters.
 */
public final class AssistantController {

    private static final Logger logger = LoggerFactory.getLogger(AssistantController.class);
    private static final long APPROVAL_WAIT_MINUTES = 5;

    /** A tool call waiting for the user's inline approval card. */
    public record PendingApproval(ChatMessage.ToolCallRecord call,
                                  CompletableFuture<Boolean> future) {
    }

    /** Last endpoint probe outcome (UI header state). */
    public record ProbeState(boolean online, String modelId, long atMillis, String error) {
    }

    private final Supplier<McpToolRegistry> registrySupplier;
    private final Supplier<AssistantSettings> settingsSupplier;
    private final Supplier<String> projectNameSupplier;
    private final ObjectMapper mapper;
    private final LlmClient client;
    private final ChatPersistence persistence;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "assistant-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile ChatSession session = new ChatSession();
    private volatile boolean busy;
    private volatile LlmClient.StreamHandle activeStream;
    private volatile PendingApproval pendingApproval;
    private volatile ProbeState probeState = new ProbeState(false, null, 0, "not probed yet");
    private volatile boolean libalexAvailable;
    private final Set<String> sessionAllowedTools = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public AssistantController(Supplier<McpToolRegistry> registrySupplier,
                               Supplier<AssistantSettings> settingsSupplier,
                               Supplier<String> projectNameSupplier,
                               ObjectMapper mapper) {
        this.registrySupplier = registrySupplier;
        this.settingsSupplier = settingsSupplier;
        this.projectNameSupplier = projectNameSupplier;
        this.mapper = mapper;
        this.client = new LlmClient(mapper);
        this.persistence = new ChatPersistence(mapper);
    }

    // ------------------------------------------------------------ UI-thread API

    public ChatSession session() {
        return session;
    }

    public boolean isBusy() {
        return busy;
    }

    public ProbeState probeState() {
        return probeState;
    }

    public PendingApproval pendingApproval() {
        return pendingApproval;
    }

    public ChatPersistence persistence() {
        return persistence;
    }

    public void setLibalexAvailable(boolean available) {
        this.libalexAvailable = available;
    }

    /**
     * Effective context-token limit for the session's (or probed) model —
     * settings override wins, else the known-model table.
     */
    public long contextLimit() {
        AssistantSettings settings = settingsSupplier.get();
        if (settings.contextTokensOverride() > 0) {
            return settings.contextTokensOverride();
        }
        String model = session.modelId() != null ? session.modelId() : probeState.modelId();
        return ModelInfo.of(model).contextTokens();
    }

    /**
     * Compact the conversation: ask the model for a dense summary, then
     * replace the history with that summary (a fresh context that remembers
     * the decisions). Runs on the worker; no-op while a turn is running.
     */
    public void compactAsync() {
        if (busy) {
            return;
        }
        busy = true;
        ChatSession target = session;
        worker.submit(() -> {
            try {
                compactNow(target, "requested with /compact");
            } catch (Throwable t) {
                logger.error("Compaction failed", t);
                appendNotice(target, "compaction failed: " + t.getMessage());
            } finally {
                busy = false;
                persistence.save(session);
            }
        });
    }

    /** Send a user message; no-op while a turn is running. */
    public void send(String text) {
        if (busy || text == null || text.isBlank()) {
            return;
        }
        busy = true;
        ChatSession target = session;
        target.add(ChatMessage.of(ChatMessage.Role.USER, text.strip(), System.currentTimeMillis()));
        worker.submit(() -> {
            try {
                runTurn(target);
            } catch (Throwable t) {
                logger.error("Assistant turn crashed", t);
                appendNotice(target, "internal error: " + t);
            } finally {
                busy = false;
                persistence.save(target);
            }
        });
    }

    /** Cancel the running turn: aborts the stream, denies pending approvals. */
    public void cancel() {
        LlmClient.StreamHandle handle = activeStream;
        if (handle != null) {
            handle.cancel();
        }
        PendingApproval approval = pendingApproval;
        if (approval != null) {
            approval.future().complete(false);
        }
    }

    /** Resolve the pending inline approval card. */
    public void resolveApproval(boolean approved, boolean alwaysThisSession) {
        PendingApproval approval = pendingApproval;
        if (approval != null) {
            if (approved && alwaysThisSession) {
                sessionAllowedTools.add(approval.call().name);
            }
            approval.future().complete(approved);
        }
    }

    public void newChat() {
        cancel();
        session = new ChatSession();
        sessionAllowedTools.clear();
        persistence.startNew();
    }

    /** Replace the session with a saved transcript. */
    public void loadChat(String fileName) {
        try {
            session = persistence.load(fileName);
        } catch (IOException e) {
            appendNotice(session, "failed to load chat: " + e.getMessage());
        }
    }

    /** Async endpoint probe (header indicator + pre-send). */
    public void probeAsync() {
        worker.submit(this::probeNow);
    }

    public void shutdown() {
        cancel();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------- the loop

    private ProbeState probeNow() {
        AssistantSettings settings = settingsSupplier.get();
        try {
            List<String> models = client.probeModels(settings.endpoint(), settings.apiKey());
            ProbeState state = new ProbeState(!models.isEmpty(),
                    models.isEmpty() ? null : models.get(0), System.currentTimeMillis(),
                    models.isEmpty() ? "no models served" : null);
            probeState = state;
            return state;
        } catch (IOException | RuntimeException e) {
            ProbeState state = new ProbeState(false, null, System.currentTimeMillis(),
                    String.valueOf(e.getMessage()));
            probeState = state;
            return state;
        }
    }

    /** Worker-thread compaction. Returns true when the history was replaced. */
    private boolean compactNow(ChatSession target, String why) {
        AssistantSettings settings = settingsSupplier.get();
        ProbeState probe = probeNow();
        if (!probe.online()) {
            appendNotice(target, "cannot compact — LLM server unreachable");
            return false;
        }
        List<ChatMessage> history = target.snapshot();
        if (history.size() < 2) {
            appendNotice(target, "nothing to compact yet");
            return false;
        }
        StringBuilder transcript = new StringBuilder();
        for (ChatMessage m : history) {
            if (m.role == ChatMessage.Role.USER) {
                transcript.append("USER: ").append(m.text).append('\n');
            } else if (m.role == ChatMessage.Role.ASSISTANT && m.text.length() > 0) {
                transcript.append("ASSISTANT: ").append(m.text).append('\n');
            } else if (m.role == ChatMessage.Role.ASSISTANT) {
                for (ChatMessage.ToolCallRecord call : m.toolCalls) {
                    transcript.append("TOOL ").append(call.name).append(" -> ")
                            .append(call.status).append('\n');
                }
            }
        }
        String prompt = "Summarize this Open Mason editing session compactly for context "
                + "carry-over. Preserve: the user's goals and decisions, the current state of "
                + "the model/scene, file/script names touched, unresolved tasks, and any "
                + "constraints the user stated. Omit pleasantries and tool minutiae. "
                + "Use terse markdown bullets.\n\n" + clipForCompaction(transcript.toString());
        ObjectNode body = mapper.createObjectNode();
        body.put("model", probe.modelId());
        body.put("temperature", 0.2f);
        ArrayNode messages = body.putArray("messages");
        ObjectNode m = messages.addObject();
        m.put("role", "user");
        m.put("content", prompt);
        StringBuilder summary = new StringBuilder();
        LlmClient.StreamHandle handle = new LlmClient.StreamHandle();
        activeStream = handle;
        try {
            client.streamChat(settings.endpoint(), settings.apiKey(), body, delta -> {
                if (delta instanceof StreamDelta.Content c) {
                    summary.append(c.text());
                }
            }, handle);
        } catch (IOException e) {
            appendNotice(target, "compaction failed: " + e.getMessage());
            return false;
        } finally {
            activeStream = null;
        }
        if (handle.isCancelled() || summary.length() == 0) {
            appendNotice(target, "compaction cancelled");
            return false;
        }
        ChatSession fresh = new ChatSession();
        fresh.setModelId(target.modelId());
        ChatMessage summaryMessage = new ChatMessage(ChatMessage.Role.SYSTEM,
                System.currentTimeMillis());
        summaryMessage.text.append("Compacted summary of the earlier conversation (")
                .append(why).append("):\n").append(summary.toString().strip());
        fresh.add(summaryMessage);
        ChatMessage note = new ChatMessage(ChatMessage.Role.ASSISTANT, System.currentTimeMillis());
        note.notice = "context compacted — earlier messages replaced by a summary";
        fresh.add(note);
        session = fresh;
        persistence.startNew();
        persistence.save(fresh);
        return true;
    }

    private static String clipForCompaction(String transcript) {
        int cap = 60_000;
        if (transcript.length() <= cap) {
            return transcript;
        }
        return "[earlier portion omitted]\n" + transcript.substring(transcript.length() - cap);
    }

    private void runTurn(ChatSession target) {
        AssistantSettings settings = settingsSupplier.get();
        ProbeState probe = probeNow();
        if (!probe.online()) {
            appendNotice(target, "LLM server unreachable at " + settings.endpoint()
                    + " (" + probe.error() + ") — start the LLM server (vLLM/SGLang) and retry");
            return;
        }
        String modelId = probe.modelId();
        if (target.modelId() == null) {
            target.setModelId(modelId);
        } else if (!target.modelId().equals(modelId)) {
            appendNotice(target, "model changed: " + target.modelId() + " -> " + modelId
                    + " (context budget adjusted)");
            target.setModelId(modelId);
        }
        ModelInfo info = ModelInfo.of(modelId);
        long contextTokens = settings.contextTokensOverride() > 0
                ? settings.contextTokensOverride() : info.contextTokens();

        // Auto-compaction: when the last known prompt size crosses 75% of the
        // limit, fold history into a summary before this turn.
        if (settings.autoCompact() && target.promptTokens() > 0
                && target.promptTokens() > contextLimit() * 3 / 4) {
            ChatMessage pendingUser = null;
            List<ChatMessage> snapshot = target.snapshot();
            if (!snapshot.isEmpty()
                    && snapshot.get(snapshot.size() - 1).role == ChatMessage.Role.USER) {
                pendingUser = snapshot.get(snapshot.size() - 1);
            }
            if (compactNow(target, "auto, context was "
                    + String.format("%,d", target.promptTokens()) + " tokens")) {
                ChatSession fresh = session; // compaction swapped the session
                if (pendingUser != null) {
                    fresh.add(pendingUser); // re-attach the message that triggered the turn
                }
                runTurn(fresh); // continue the turn on the compacted session
                return;
            }
        }

        McpToolRegistry registry = registrySupplier.get();
        ToolBridge bridge = registry != null ? new ToolBridge(registry, mapper) : null;
        List<ObjectNode> toolSpecs = bridge != null ? bridge.openAiToolSpecs() : List.of();

        for (int iteration = 0; iteration < Math.max(1, settings.maxToolIterations()); iteration++) {
            ContextBudget.Result fitted = buildEntries(target, settings, modelId, info);
            if (fitted.truncated()) {
                appendNotice(target, "older messages were trimmed to fit the context window");
            }
            ObjectNode body = buildBody(modelId, settings, fitted.entries(), toolSpecs);

            ChatMessage assistant = new ChatMessage(ChatMessage.Role.ASSISTANT,
                    System.currentTimeMillis());
            target.add(assistant);
            SseParser.ToolCallAccumulator accumulator = new SseParser.ToolCallAccumulator();
            String[] finishReason = {null};
            LlmClient.StreamHandle handle = new LlmClient.StreamHandle();
            activeStream = handle;
            try {
                client.streamChat(settings.endpoint(), settings.apiKey(), body, delta -> {
                    switch (delta) {
                        case StreamDelta.Content c -> {
                            assistant.text.append(c.text());
                            target.touch();
                        }
                        case StreamDelta.Reasoning r -> {
                            assistant.reasoning.append(r.text());
                            target.touch();
                        }
                        case StreamDelta.ToolCallFragment f -> accumulator.accept(f);
                        case StreamDelta.Finish f -> finishReason[0] = f.reason();
                        case StreamDelta.Usage u ->
                                target.recordUsage(u.promptTokens(), u.completionTokens());
                    }
                }, handle);
            } catch (IOException e) {
                assistant.notice = handle.isCancelled() ? "[cancelled]"
                        : "[stream error: " + e.getMessage() + "]";
                target.touch();
                return;
            } finally {
                activeStream = null;
            }
            if (handle.isCancelled()) {
                assistant.notice = "[cancelled]";
                target.touch();
                return;
            }

            boolean wantsTools = "tool_calls".equals(finishReason[0]) && !accumulator.isEmpty();
            if (!wantsTools) {
                if ("length".equals(finishReason[0])) {
                    assistant.notice = "[output truncated by max tokens]";
                    target.touch();
                }
                return; // normal completion
            }
            if (bridge == null) {
                appendNotice(target, "tool registry unavailable — cannot execute tool calls");
                return;
            }

            // Serialized tool dispatch.
            for (SseParser.ToolCallAccumulator.Call call : accumulator.calls()) {
                ChatMessage.ToolCallRecord record =
                        new ChatMessage.ToolCallRecord(call.id(), call.name(), call.argumentsJson());
                assistant.toolCalls.add(record);
                target.touch();
                String resultText = executeCall(target, bridge, record, settings);
                ChatMessage toolMessage = new ChatMessage(ChatMessage.Role.TOOL,
                        System.currentTimeMillis(), record.id);
                toolMessage.text.append(resultText);
                target.add(toolMessage);
            }
            persistence.save(target);
            if (iteration == Math.max(1, settings.maxToolIterations()) - 1) {
                // Budget exhausted — one final summarize pass without tools.
                appendNotice(target, "tool-iteration budget exhausted — asking the model to wrap up");
                toolSpecs = List.of();
            }
        }
    }

    private String executeCall(ChatSession target, ToolBridge bridge,
                               ChatMessage.ToolCallRecord record, AssistantSettings settings) {
        ToolCategory category = bridge.categorize(record.name);
        AssistantSettings.ApprovalPolicy policy = switch (category) {
            case READ_ONLY -> settings.readOnlyPolicy();
            case MUTATING -> settings.mutatingPolicy();
            case REQUIRES_AUTH -> settings.requiresAuthPolicy();
        };
        if (policy == AssistantSettings.ApprovalPolicy.DENY) {
            record.status = ChatMessage.CallStatus.DENIED;
            record.resultText = "Denied by user policy (" + category + " tools are disabled)";
            target.touch();
            return record.resultText;
        }
        if (policy == AssistantSettings.ApprovalPolicy.ASK
                && !sessionAllowedTools.contains(record.name)) {
            record.status = ChatMessage.CallStatus.AWAITING_APPROVAL;
            target.touch();
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            pendingApproval = new PendingApproval(record, future);
            boolean approved;
            try {
                approved = future.get(APPROVAL_WAIT_MINUTES, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                approved = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                approved = false;
            } catch (java.util.concurrent.ExecutionException e) {
                approved = false;
            } finally {
                pendingApproval = null;
            }
            if (!approved) {
                record.status = ChatMessage.CallStatus.DENIED;
                record.resultText = "Denied by the user";
                target.touch();
                return record.resultText;
            }
        }
        record.status = ChatMessage.CallStatus.RUNNING;
        target.touch();
        ToolBridge.DispatchResult result = bridge.dispatch(record.name, record.argumentsJson);
        record.status = result.error() ? ChatMessage.CallStatus.ERROR : ChatMessage.CallStatus.OK;
        record.resultText = result.text();
        record.image = result.image();
        target.touch();
        return result.text();
    }

    // ------------------------------------------------------------- plumbing

    private ContextBudget.Result buildEntries(ChatSession target, AssistantSettings settings,
                                              String modelId, ModelInfo info) {
        List<ContextBudget.Entry> entries = new ArrayList<>();
        entries.add(new ContextBudget.Entry("system",
                SystemPromptBuilder.build(modelId, projectNameSupplier.get(), libalexAvailable,
                        PythonScriptEngine.ifAvailable() != null, settings.promptExtras()),
                null, null));
        for (ChatMessage m : target.snapshot()) {
            switch (m.role) {
                case SYSTEM -> entries.add(new ContextBudget.Entry("system",
                        m.text.toString(), null, null));
                case USER -> entries.add(new ContextBudget.Entry("user",
                        m.text.toString(), null, null));
                case ASSISTANT -> {
                    if (m.text.length() > 0 || !m.toolCalls.isEmpty()) {
                        entries.add(new ContextBudget.Entry("assistant", m.text.toString(), null,
                                m.toolCalls.isEmpty() ? null : new ArrayList<>(m.toolCalls)));
                    }
                }
                case TOOL -> entries.add(new ContextBudget.Entry("tool",
                        m.text.toString(), m.toolCallId, null));
            }
        }
        long contextTokens = settings.contextTokensOverride() > 0
                ? settings.contextTokensOverride() : info.contextTokens();
        return ContextBudget.fit(entries, contextTokens, info.maxOutputTokens());
    }

    private ObjectNode buildBody(String modelId, AssistantSettings settings,
                                 List<ContextBudget.Entry> entries, List<ObjectNode> toolSpecs) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelId);
        body.put("temperature", settings.temperature());
        ArrayNode messages = body.putArray("messages");
        for (ContextBudget.Entry entry : entries) {
            ObjectNode m = messages.addObject();
            m.put("role", entry.role());
            if ("tool".equals(entry.role())) {
                m.put("tool_call_id", entry.toolCallId());
                m.put("content", entry.content());
                continue;
            }
            if (entry.toolCalls() != null && !entry.toolCalls().isEmpty()) {
                if (entry.content() != null && !entry.content().isEmpty()) {
                    m.put("content", entry.content());
                } else {
                    m.putNull("content");
                }
                ArrayNode calls = m.putArray("tool_calls");
                for (ChatMessage.ToolCallRecord call : entry.toolCalls()) {
                    ObjectNode c = calls.addObject();
                    c.put("id", call.id);
                    c.put("type", "function");
                    ObjectNode fn = c.putObject("function");
                    fn.put("name", call.name);
                    fn.put("arguments", call.argumentsJson == null || call.argumentsJson.isBlank()
                            ? "{}" : call.argumentsJson);
                }
            } else {
                m.put("content", entry.content());
            }
        }
        if (!toolSpecs.isEmpty()) {
            body.set("tools", client.toolsArray(toolSpecs));
            body.put("tool_choice", "auto");
        }
        return body;
    }

    /** UI-thread helper: drop an informational notice into the chat. */
    public void appendLocalNotice(String notice) {
        appendNotice(session, notice);
    }

    private void appendNotice(ChatSession target, String notice) {
        ChatMessage m = new ChatMessage(ChatMessage.Role.ASSISTANT, System.currentTimeMillis());
        m.notice = notice;
        target.add(m);
    }

    // test seam
    Set<String> sessionAllowedToolsView() {
        return new HashSet<>(sessionAllowedTools);
    }
}
