package com.openmason.main.systems.assistant.ui;

import com.openmason.main.systems.assistant.AssistantController;
import com.openmason.main.systems.assistant.ChatMessage;
import com.openmason.main.systems.assistant.ChatSession;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.util.List;

/**
 * The Assistant chat pane — a standard docked pane (docked into the right
 * stack by {@code MainLayoutBuilder}, layout version 4, so it can never
 * appear off-window on first load).
 */
public class AssistantPaneImGui {

    public static final String WINDOW_TITLE = "Assistant";
    private static final long PROBE_INTERVAL_MS = 15_000;

    private final AssistantController controller;
    private final ChatMessageRenderer renderer = new ChatMessageRenderer();
    private final ImString input = new ImString(8 * 1024);
    private final ImBoolean alwaysAllow = new ImBoolean(false);
    private long lastProbeRequest;
    private long lastSeenRevision = -1;
    private ChatSession lastSession;
    private boolean stickToBottom = true;
    /** Bumped on send: a fresh widget id is the one reliable way to clear an
     *  active InputText (its internal edit buffer survives ImString.set). */
    private int inputGeneration;
    private boolean focusInputNextFrame;

    public AssistantPaneImGui(AssistantController controller) {
        this.controller = controller;
    }

    /** Release Skija/GL resources. Must run before the SkijaContext closes. */
    public void dispose() {
        renderer.close();
    }

    public void render(ImBoolean visible) {
        if (!visible.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastProbeRequest > PROBE_INTERVAL_MS) {
            lastProbeRequest = now;
            controller.probeAsync();
        }
        if (ImGui.begin(WINDOW_TITLE, visible, 0)) {
            ChatSession session = controller.session();
            if (session != lastSession) {
                renderer.clearImages();
                lastSession = session;
                lastSeenRevision = -1;
            }
            renderHeader(session);
            ImGui.separator();
            float inputHeight = computeInputHeight();
            ImGui.beginChild("##assistantMessages", 0,
                    ImGui.getContentRegionAvailY() - inputHeight - 8, false);
            List<ChatMessage> messages = session.snapshot();
            if (messages.isEmpty()) {
                ImGui.textDisabled("Ask the local model to inspect or edit the open model.\n"
                        + "It can call every Open Mason tool. Enter sends; Shift+Enter = newline.");
            }
            renderer.render(messages);
            renderApprovalCard();
            if (controller.isBusy() && controller.pendingApproval() == null) {
                int dots = (int) (ImGui.getTime() * 2.5) % 4;
                ImGui.spacing();
                ImGui.textColored(0.55f, 0.65f, 0.85f, 1f,
                        workingLabel(messages) + ".".repeat(dots));
            }
            // Auto-scroll while new content arrives, unless the user scrolled up.
            if (ImGui.getScrollY() < ImGui.getScrollMaxY() - 24) {
                stickToBottom = false;
            }
            if (ImGui.getScrollY() >= ImGui.getScrollMaxY() - 4) {
                stickToBottom = true;
            }
            long revision = session.revision();
            if (stickToBottom && revision != lastSeenRevision) {
                ImGui.setScrollHereY(1.0f);
            }
            lastSeenRevision = revision;
            ImGui.endChild();
            renderInput(inputHeight);
        }
        ImGui.end();
    }

    private void renderHeader(ChatSession session) {
        AssistantController.ProbeState probe = controller.probeState();
        if (probe.online()) {
            ImGui.textColored(0.4f, 0.9f, 0.4f, 1f, "*");
            ImGui.sameLine();
            ImGui.text(probe.modelId() == null ? "?" : probe.modelId());
            String sessionModel = session.modelId();
            if (sessionModel != null && probe.modelId() != null
                    && !sessionModel.equals(probe.modelId())) {
                ImGui.sameLine();
                ImGui.textColored(1f, 0.75f, 0.3f, 1f,
                        "(chat used " + sessionModel + " — next turn adopts)");
            }
        } else {
            ImGui.textColored(1f, 0.4f, 0.4f, 1f, "* offline");
            if (ImGui.isItemHovered() && probe.error() != null) {
                ImGui.setTooltip(probe.error());
            }
            ImGui.sameLine();
            if (ImGui.smallButton("Retry")) {
                controller.probeAsync();
            }
        }
        long used = session.promptTokens();
        long limit = controller.contextLimit();
        if (limit > 0) {
            ImGui.sameLine();
            float fraction = Math.min(1f, used / (float) limit);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.PlotHistogram,
                    fraction < 0.6f ? 0.35f : fraction < 0.85f ? 0.85f : 0.95f,
                    fraction < 0.6f ? 0.65f : fraction < 0.85f ? 0.65f : 0.35f,
                    0.35f, 1f);
            ImGui.progressBar(fraction, 110, 15,
                    String.format("%,d / %,d", used, limit));
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(String.format(
                        "Context: %,d of %,d tokens used (%.0f%%) — %,d left%n"
                        + "/compact folds history into a summary; /clear starts fresh",
                        used, limit, fraction * 100, Math.max(0, limit - used)));
            }
        }
        ImGui.sameLine(Math.max(200, ImGui.getContentRegionAvailX() - 130));
        if (ImGui.smallButton("New Chat")) {
            controller.newChat();
        }
        ImGui.sameLine();
        if (ImGui.smallButton("History")) {
            ImGui.openPopup("##chatHistory");
        }
        if (ImGui.beginPopup("##chatHistory")) {
            List<String> saved = controller.persistence().listSaved();
            if (saved.isEmpty()) {
                ImGui.textDisabled("No saved chats");
            }
            for (String file : saved) {
                if (ImGui.selectable(file)) {
                    controller.loadChat(file);
                }
            }
            ImGui.endPopup();
        }
    }

    private void renderApprovalCard() {
        AssistantController.PendingApproval pending = controller.pendingApproval();
        if (pending == null) {
            return;
        }
        ImGui.spacing();
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.30f, 0.24f, 0.08f, 1.0f);
        ImGui.beginChild("##approvalCard", 0, 118, true);
        ImGui.textColored(1f, 0.85f, 0.4f, 1f, "Approval needed");
        ImGui.textWrapped("The assistant wants to run: " + pending.call().name);
        ImGui.textDisabled(clipArgs(pending.call().argumentsJson));
        if (ImGui.button("Approve")) {
            controller.resolveApproval(true, alwaysAllow.get());
            alwaysAllow.set(false);
        }
        ImGui.sameLine();
        if (ImGui.button("Deny")) {
            controller.resolveApproval(false, false);
            alwaysAllow.set(false);
        }
        ImGui.sameLine();
        ImGui.checkbox("Always allow this session", alwaysAllow);
        ImGui.endChild();
        ImGui.popStyleColor();
    }

    /** One text line when empty, grows with wrapped content up to ~6 lines. */
    private float computeInputHeight() {
        float lineHeight = ImGui.getTextLineHeight();
        float charWidth = Math.max(4f, ImGui.calcTextSize("M").x); // mono UI font
        float innerWidth = Math.max(60, ImGui.getContentRegionAvailX() - 76 - 24);
        int cols = Math.max(8, (int) (innerWidth / charWidth));
        int lines = 0;
        for (String hardLine : input.get().split("\n", -1)) {
            lines += Math.max(1, (hardLine.length() + cols - 1) / cols);
        }
        lines = Math.max(1, Math.min(lines, 6)); // scroll inside beyond 6
        return lines * lineHeight + 14;
    }

    private void renderInput(float height) {
        boolean busy = controller.isBusy();
        if (focusInputNextFrame) {
            ImGui.setKeyboardFocusHere();
            focusInputNextFrame = false;
        }
        ImGui.inputTextMultiline("##assistantInput" + inputGeneration, input,
                ImGui.getContentRegionAvailX() - 76, height,
                ImGuiInputTextFlags.AllowTabInput | ImGuiInputTextFlags.WordWrap);
        boolean enterPressed = ImGui.isItemFocused()
                && ImGui.isKeyPressed(imgui.flag.ImGuiKey.Enter, false)
                && !ImGui.getIO().getKeyShift();
        ImGui.sameLine();
        ImGui.beginGroup();
        if (busy) {
            if (ImGui.button("Stop", 68, height)) {
                controller.cancel();
            }
        } else {
            boolean online = controller.probeState().online();
            if (!online) {
                ImGui.beginDisabled();
            }
            boolean sendClicked = ImGui.button("Send", 68, height);
            if (!online) {
                ImGui.endDisabled();
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("LLM server offline");
                }
            }
            if ((sendClicked || enterPressed) && online) {
                String text = input.get();
                // Multiline widget inserts the newline before we see the key —
                // strip a single trailing newline on Enter-send.
                if (enterPressed && text.endsWith("\n")) {
                    text = text.substring(0, text.length() - 1);
                }
                if (!text.isBlank()) {
                    dispatchInput(text.strip());
                    input.set("");
                    inputGeneration++; // new widget id -> guaranteed-empty editor
                    focusInputNextFrame = true;
                    stickToBottom = true;
                }
            }
        }
        ImGui.endGroup();
    }

    /** Slash commands are handled locally; anything else goes to the model. */
    private void dispatchInput(String text) {
        switch (text.toLowerCase(java.util.Locale.ROOT)) {
            case "/clear" -> controller.newChat();
            case "/compact" -> controller.compactAsync();
            case "/help" -> controller.appendLocalNotice(
                    "commands: /clear (new chat), /compact (summarize history into a fresh "
                    + "context), /help");
            default -> {
                if (text.startsWith("/")) {
                    controller.appendLocalNotice("unknown command '" + text
                            + "' — /clear, /compact, /help");
                } else {
                    controller.send(text);
                }
            }
        }
    }

    private String workingLabel(java.util.List<ChatMessage> messages) {
        if (!messages.isEmpty()) {
            ChatMessage last = messages.get(messages.size() - 1);
            if (last.role == ChatMessage.Role.ASSISTANT) {
                boolean reasoningLive = last.reasoning.length() > 0 && last.text.length() == 0;
                if (reasoningLive) {
                    return "thinking";
                }
                for (ChatMessage.ToolCallRecord call : last.toolCalls) {
                    if (call.status == ChatMessage.CallStatus.RUNNING) {
                        return "running " + call.name;
                    }
                }
                if (last.text.length() > 0) {
                    return "responding";
                }
            }
            if (last.role == ChatMessage.Role.TOOL) {
                return "reading tool result";
            }
        }
        return "thinking";
    }

    private static String clipArgs(String args) {
        if (args == null) {
            return "{}";
        }
        return args.length() > 220 ? args.substring(0, 220) + "…" : args;
    }
}
