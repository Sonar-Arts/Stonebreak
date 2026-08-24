package com.openmason.main.systems.assistant.ui;

import com.openmason.main.systems.assistant.ChatMessage;
import com.openmason.main.systems.mcp.McpImageContent;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTreeNodeFlags;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders chat messages: role-tinted blocks, fenced code sections with copy
 * buttons, collapsible thinking, expandable tool-call rows, inline images
 * (lazy GL upload, freed on {@link #clearImages()}).
 */
final class ChatMessageRenderer implements AutoCloseable {

    private record Uploaded(int textureId, int width, int height) {
    }

    private final ChatProseSkija prose = new ChatProseSkija();

    private final Map<ChatMessage.ToolCallRecord, Uploaded> imageCache = new HashMap<>();

    /**
     * The ImGui font atlas only covers basic Latin — common punctuation the
     * models emit (arrows, dashes, checks) renders as tofu. Map it to ASCII.
     * The Skija chat surface renders real glyphs; this guards the fallback.
     */
    static String sanitizeForImGui(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                sb.append(c);
                continue;
            }
            switch (c) {
                case '\u2192' -> sb.append("->");
                case '\u2190' -> sb.append("<-");
                case '\u2194' -> sb.append("<->");
                case '\u21d2' -> sb.append("=>");
                case '\u2013', '\u2014', '\u2212' -> sb.append('-');
                case '\u2018', '\u2019' -> sb.append('\'');
                case '\u201c', '\u201d' -> sb.append('"');
                case '\u2022', '\u25cf', '\u25aa', '\u25b8', '\u25b6' -> sb.append('*');
                case '\u2026' -> sb.append("...");
                case '\u2713', '\u2714' -> sb.append("[ok]");
                case '\u2717', '\u2718', '\u2716' -> sb.append("[x]");
                case '\u00d7' -> sb.append('x');
                case '\u00b0' -> sb.append(" deg");
                case '\u2248' -> sb.append("~=");
                case '\u2264' -> sb.append("<=");
                case '\u2265' -> sb.append(">=");
                case '\u00b1' -> sb.append("+/-");
                case '\u00a0' -> sb.append(' ');
                default -> {
                    if (!Character.isISOControl(c)) {
                        sb.append('?');
                    }
                }
            }
        }
        return sb.toString();
    }

    void render(List<ChatMessage> messages) {
        prose.beginFrame();
        int index = 0;
        for (ChatMessage message : messages) {
            renderMessage(message, index++);
        }
        prose.endFrame();
    }

    @Override
    public void close() {
        clearImages();
        prose.close();
    }

    void clearImages() {
        for (Uploaded uploaded : imageCache.values()) {
            GL11.glDeleteTextures(uploaded.textureId());
        }
        imageCache.clear();
    }

    private void renderMessage(ChatMessage message, int index) {
        switch (message.role) {
            case USER -> {
                messageGap(index);
                ImGui.pushStyleColor(ImGuiCol.Text, 0.62f, 0.80f, 1.0f, 1.0f);
                ImGui.text("You");
                ImGui.popStyleColor();
                ImGui.indent(10);
                renderProse(message, "u" + index);
                ImGui.unindent(10);
            }
            case ASSISTANT -> {
                boolean hasBody = message.text.length() > 0 || message.reasoning.length() > 0
                        || !message.toolCalls.isEmpty();
                if (hasBody) {
                    messageGap(index);
                    ImGui.pushStyleColor(ImGuiCol.Text, 0.72f, 0.95f, 0.72f, 1.0f);
                    ImGui.text("Assistant");
                    ImGui.popStyleColor();
                    ImGui.indent(10);
                }
                if (message.reasoning.length() > 0) {
                    if (ImGui.treeNodeEx("Thinking##think" + index, ImGuiTreeNodeFlags.SpanAvailWidth)) {
                        ImGui.pushStyleColor(ImGuiCol.Text, 0.6f, 0.6f, 0.65f, 1.0f);
                        ImGui.textWrapped(sanitizeForImGui(clip(message.reasoning.toString(), 8_000)));
                        ImGui.popStyleColor();
                        ImGui.treePop();
                    }
                }
                if (message.text.length() > 0) {
                    renderProse(message, "a" + index);
                }
                int callIdx = 0;
                for (ChatMessage.ToolCallRecord call : message.toolCalls) {
                    renderToolCall(call, index + "_" + callIdx++);
                }
                if (hasBody) {
                    ImGui.unindent(10);
                }
                if (message.notice != null) {
                    ImGui.textColored(1.0f, 0.75f, 0.3f, 1.0f,
                            sanitizeForImGui(message.notice));
                }
            }
            case TOOL -> {
                // Tool results render inside their call rows; nothing standalone.
            }
            case SYSTEM -> {
                messageGap(index);
                if (ImGui.treeNodeEx("Compacted summary##sys" + index,
                        ImGuiTreeNodeFlags.SpanAvailWidth)) {
                    ImGui.pushStyleColor(ImGuiCol.Text, 0.65f, 0.65f, 0.7f, 1.0f);
                    ImGui.textWrapped(sanitizeForImGui(clip(message.text.toString(), 8_000)));
                    ImGui.popStyleColor();
                    ImGui.treePop();
                }
            }
        }
    }

    private static void messageGap(int index) {
        if (index > 0) {
            ImGui.dummy(0, 6);
            ImGui.pushStyleColor(ImGuiCol.Separator, 1f, 1f, 1f, 0.06f);
            ImGui.separator();
            ImGui.popStyleColor();
            ImGui.dummy(0, 2);
        } else {
            ImGui.spacing();
        }
    }

    private void renderToolCall(ChatMessage.ToolCallRecord call, String id) {
        String icon;
        float[] color;
        switch (call.status) {
            case OK -> {
                icon = "[ok]";
                color = new float[]{0.5f, 0.85f, 0.5f, 1f};
            }
            case ERROR -> {
                icon = "[err]";
                color = new float[]{1f, 0.45f, 0.45f, 1f};
            }
            case DENIED -> {
                icon = "[denied]";
                color = new float[]{1f, 0.65f, 0.3f, 1f};
            }
            case RUNNING -> {
                icon = "[run]";
                color = new float[]{0.6f, 0.75f, 1f, 1f};
            }
            case AWAITING_APPROVAL -> {
                icon = "[?]";
                color = new float[]{1f, 0.85f, 0.4f, 1f};
            }
            default -> {
                icon = "[..]";
                color = new float[]{0.6f, 0.6f, 0.6f, 1f};
            }
        }
        ImGui.pushStyleColor(ImGuiCol.Text, color[0], color[1], color[2], color[3]);
        boolean open = ImGui.treeNodeEx(icon + " " + call.name + "##call" + id,
                ImGuiTreeNodeFlags.SpanAvailWidth);
        ImGui.popStyleColor();
        if (open) {
            ImGui.textDisabled("args:");
            ImGui.sameLine();
            ImGui.textWrapped(sanitizeForImGui(clip(call.argumentsJson == null ? "{}" : call.argumentsJson, 2_000)));
            if (call.resultText != null) {
                ImGui.textDisabled("result:");
                ImGui.textWrapped(sanitizeForImGui(clip(call.resultText, 4_000)));
                if (ImGui.smallButton("Copy result##" + id)) {
                    ImGui.setClipboardText(call.resultText);
                }
            }
            if (call.image != null) {
                renderImage(call);
            }
            ImGui.treePop();
        }
    }

    private void renderImage(ChatMessage.ToolCallRecord call) {
        Uploaded uploaded = imageCache.get(call);
        if (uploaded == null) {
            uploaded = upload(call.image);
            if (uploaded == null) {
                ImGui.textDisabled("[image could not be decoded]");
                return;
            }
            imageCache.put(call, uploaded);
        }
        float maxWidth = Math.max(64, ImGui.getContentRegionAvailX() - 8);
        float scale = Math.min(1f, maxWidth / uploaded.width());
        ImGui.image(uploaded.textureId(), uploaded.width() * scale, uploaded.height() * scale);
    }

    private static Uploaded upload(McpImageContent image) {
        try {
            byte[] png = Base64.getDecoder().decode(image.base64Data());
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
            if (decoded == null) {
                return null;
            }
            int w = decoded.getWidth();
            int h = decoded.getHeight();
            int[] argb = decoded.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buffer = BufferUtils.createByteBuffer(w * h * 4);
            for (int px : argb) {
                buffer.put((byte) ((px >> 16) & 0xFF));
                buffer.put((byte) ((px >> 8) & 0xFF));
                buffer.put((byte) (px & 0xFF));
                buffer.put((byte) ((px >>> 24) & 0xFF));
            }
            buffer.flip();
            int textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            return new Uploaded(textureId, w, h);
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    /**
     * Message body: Skija paragraph rendering (markdown, real glyphs) when the
     * Skija stack is up; ImGui wrapped-text fallback otherwise.
     */
    private void renderProse(ChatMessage message, String id) {
        if (!prose.isAvailable()) {
            wrappedWithCode(message.text.toString(), id);
            return;
        }
        float width = Math.max(60, ImGui.getContentRegionAvailX() - 4);
        int codeIdx = 0;
        for (ChatProseSkija.Piece piece : prose.pieces(message, width)) {
            if (piece instanceof ChatProseSkija.Piece.Prose p) {
                prose.renderRun(message, p.poolIndex(), width,
                        "##proseCtx" + id + "_" + p.poolIndex());
            } else if (piece instanceof ChatProseSkija.Piece.Code c) {
                codeChild(c.body(), id + "_sk" + codeIdx++);
            }
        }
    }

    /** Fenced code as an ImGui child: selectable-ish, mono, with Copy. */
    private void codeChild(String code, String id) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.10f, 0.10f, 0.12f, 1.0f);
        float height = Math.min(260, 24 + 17f * (count(code, '\n') + 1));
        ImGui.beginChild("##code" + id, 0, height, true);
        ImGui.textUnformatted(sanitizeForImGui(code));
        ImGui.endChild();
        ImGui.popStyleColor();
        if (ImGui.smallButton("Copy##code" + id)) {
            ImGui.setClipboardText(code);
        }
    }

    /** Prose with ``` fenced blocks rendered as copyable code sections. */
    private void wrappedWithCode(String text, String id) {
        String[] segments = text.split("```", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (i % 2 == 0) {
                if (!segment.isBlank()) {
                    ImGui.textWrapped(sanitizeForImGui(segment.strip()));
                }
            } else {
                // First line may be a language tag — drop it from display.
                String code = segment;
                int nl = code.indexOf('\n');
                if (nl >= 0 && nl < 20 && !code.substring(0, nl).contains(" ")) {
                    code = code.substring(nl + 1);
                }
                codeChild(code.strip(), id + "_" + i);
            }
        }
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + " …[+" + (s.length() - max) + " chars]";
    }
}
