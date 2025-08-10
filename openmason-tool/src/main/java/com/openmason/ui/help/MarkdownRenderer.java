package com.openmason.ui.help;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * ImGui-based Markdown renderer for help content.
 * 
 * Renders common Markdown elements using Dear ImGui primitives:
 * - Headers (H1, H2, H3)
 * - Lists (bulleted and numbered)  
 * - Code blocks and inline code
 * - Links (internal and external)
 * - Text formatting (bold, italic)
 * - Horizontal rules
 * - Blockquotes
 */
public class MarkdownRenderer {
    
    private static final Logger logger = LoggerFactory.getLogger(MarkdownRenderer.class);
    
    // Regex patterns for markdown parsing
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^\\)]+)\\)");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("\\*([^*]+)\\*");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    
    // Rendering configuration
    private float headerScale1 = 1.3f;
    private float headerScale2 = 1.15f;
    private float headerScale3 = 1.05f;
    private float codeBlockPadding = 8.0f;
    private float linkColorR = 0.4f, linkColorG = 0.7f, linkColorB = 1.0f;
    
    // Link click handler
    private Consumer<String> linkClickHandler;
    
    public MarkdownRenderer() {
        this.linkClickHandler = this::handleDefaultLinkClick;
    }
    
    public MarkdownRenderer(Consumer<String> linkClickHandler) {
        this.linkClickHandler = linkClickHandler != null ? linkClickHandler : this::handleDefaultLinkClick;
    }
    
    /**
     * Render markdown text using Dear ImGui primitives.
     * 
     * @param markdown The markdown content to render
     */
    public void renderMarkdown(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            ImGui.textDisabled("No content available.");
            return;
        }
        
        try {
            String[] lines = markdown.split("\\n");
            boolean inCodeBlock = false;
            StringBuilder codeBlockContent = new StringBuilder();
            
            for (String line : lines) {
                // Handle code blocks
                if (line.trim().startsWith("```")) {
                    if (inCodeBlock) {
                        // End of code block
                        renderCodeBlock(codeBlockContent.toString());
                        codeBlockContent.setLength(0);
                        inCodeBlock = false;
                    } else {
                        // Start of code block
                        inCodeBlock = true;
                    }
                    continue;
                }
                
                if (inCodeBlock) {
                    codeBlockContent.append(line).append("\n");
                    continue;
                }
                
                // Handle regular lines
                if (line.trim().isEmpty()) {
                    ImGui.spacing();
                    continue;
                }
                
                renderLine(line);
            }
            
            // Handle unclosed code block
            if (inCodeBlock && codeBlockContent.length() > 0) {
                renderCodeBlock(codeBlockContent.toString());
            }
            
        } catch (Exception e) {
            logger.error("Error rendering markdown content", e);
            ImGui.textColored(1.0f, 0.3f, 0.3f, 1.0f, "Error rendering content: " + e.getMessage());
        }
    }
    
    /**
     * Render a single line of markdown content.
     */
    private void renderLine(String line) {
        String trimmed = line.trim();
        
        // Headers
        if (trimmed.startsWith("### ")) {
            renderHeader(trimmed.substring(4), 3);
        } else if (trimmed.startsWith("## ")) {
            renderHeader(trimmed.substring(3), 2);
        } else if (trimmed.startsWith("# ")) {
            renderHeader(trimmed.substring(2), 1);
        }
        // Horizontal rule
        else if (trimmed.equals("---") || trimmed.equals("***")) {
            ImGui.separator();
            ImGui.spacing();
        }
        // Blockquote
        else if (trimmed.startsWith("> ")) {
            renderBlockquote(trimmed.substring(2));
        }
        // Unordered list
        else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            renderBulletPoint(trimmed.substring(2));
        }
        // Ordered list (simple)
        else if (trimmed.matches("^\\d+\\. .*")) {
            int dotIndex = trimmed.indexOf(". ");
            String number = trimmed.substring(0, dotIndex);
            String content = trimmed.substring(dotIndex + 2);
            renderOrderedListItem(number, content);
        }
        // Single line code
        else if (trimmed.startsWith("`") && trimmed.endsWith("`") && trimmed.length() > 2) {
            renderInlineCodeLine(trimmed);
        }
        // Regular text with formatting
        else {
            renderFormattedText(line);
        }
    }
    
    /**
     * Render a header with appropriate styling.
     */
    private void renderHeader(String text, int level) {
        ImGui.spacing();
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 6);
        
        switch (level) {
            case 1 -> {
                // Header 1 - largest, use bright white with prefix
                ImGui.textColored(1.0f, 1.0f, 1.0f, 1.0f, "# " + text.toUpperCase());
                ImGui.separator();
            }
            case 2 -> {
                // Header 2 - medium, slightly dimmer with prefix
                ImGui.textColored(0.95f, 0.95f, 0.95f, 1.0f, "## " + text);
                ImGui.separator();
            }
            case 3 -> {
                // Header 3 - smallest, dimmer with prefix
                ImGui.textColored(0.9f, 0.9f, 0.9f, 1.0f, "### " + text);
            }
        }
        
        ImGui.popStyleVar();
        ImGui.spacing();
    }
    
    /**
     * Render a bullet point list item.
     */
    private void renderBulletPoint(String text) {
        ImGui.bullet();
        ImGui.sameLine();
        renderFormattedText(text);
    }
    
    /**
     * Render an ordered list item.
     */
    private void renderOrderedListItem(String number, String text) {
        ImGui.text(number + ".");
        ImGui.sameLine();
        renderFormattedText(text);
    }
    
    /**
     * Render a blockquote.
     */
    private void renderBlockquote(String text) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.0f, 0.3f, 0.6f, 0.1f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 4.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        
        if (ImGui.beginChild("blockquote", 0, 0, true, ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8, 4);
            ImGui.text("|");
            ImGui.sameLine();
            renderFormattedText(text);
            ImGui.popStyleVar();
        }
        ImGui.endChild();
        
        ImGui.popStyleVar(2);
        ImGui.popStyleColor();
    }
    
    /**
     * Render a code block with syntax highlighting background.
     */
    private void renderCodeBlock(String code) {
        ImGui.spacing();
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.12f, 0.12f, 0.12f, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 6.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, codeBlockPadding, codeBlockPadding);
        
        float contentHeight = ImGui.calcTextSize(code).y + (codeBlockPadding * 2);
        
        if (ImGui.beginChild("code_block", 0, contentHeight, true)) {
            // Use monospace-style formatting
            ImGui.pushStyleColor(ImGuiCol.Text, 0.9f, 0.9f, 0.9f, 1.0f);
            String[] codeLines = code.split("\\n");
            for (String codeLine : codeLines) {
                ImGui.text(codeLine);
            }
            ImGui.popStyleColor();
        }
        ImGui.endChild();
        
        ImGui.popStyleVar(2);
        ImGui.popStyleColor();
        ImGui.spacing();
    }
    
    /**
     * Render a single line of inline code.
     */
    private void renderInlineCodeLine(String code) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.15f, 0.15f, 0.15f, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 3.0f);
        
        if (ImGui.beginChild("inline_code", 0, ImGui.getTextLineHeight() + 6, true)) {
            ImGui.pushStyleColor(ImGuiCol.Text, 0.9f, 0.9f, 0.9f, 1.0f);
            ImGui.text(code.substring(1, code.length() - 1)); // Remove surrounding backticks
            ImGui.popStyleColor();
        }
        ImGui.endChild();
        
        ImGui.popStyleVar();
        ImGui.popStyleColor();
    }
    
    /**
     * Render text with inline formatting (bold, italic, links, inline code).
     */
    private void renderFormattedText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        
        // Check if text contains any formatting
        if (!containsFormatting(text)) {
            ImGui.textWrapped(text);
            return;
        }
        
        renderTextWithFormatting(text);
    }
    
    /**
     * Check if text contains any markdown formatting.
     */
    private boolean containsFormatting(String text) {
        return text.contains("**") || text.contains("*") || 
               text.contains("[") || text.contains("`");
    }
    
    /**
     * Render text with complex formatting, handling multiple format types.
     */
    private void renderTextWithFormatting(String text) {
        String remaining = text;
        boolean hasRenderedContent = false;
        
        while (!remaining.isEmpty()) {
            // Find the next formatting element
            FormatMatch nextMatch = findNextFormat(remaining);
            
            if (nextMatch == null) {
                // No more formatting, render remaining text
                if (!remaining.isEmpty()) {
                    if (hasRenderedContent) ImGui.sameLine();
                    ImGui.textWrapped(remaining);
                }
                break;
            }
            
            // Render text before the formatting
            if (nextMatch.start > 0) {
                String beforeText = remaining.substring(0, nextMatch.start);
                if (hasRenderedContent) ImGui.sameLine();
                ImGui.text(beforeText);
                hasRenderedContent = true;
            }
            
            // Render the formatted element
            if (hasRenderedContent) ImGui.sameLine();
            renderFormattedElement(nextMatch);
            hasRenderedContent = true;
            
            // Continue with text after the formatting
            remaining = remaining.substring(nextMatch.end);
        }
        
        if (!hasRenderedContent) {
            ImGui.textWrapped(text);
        }
    }
    
    /**
     * Find the next formatting element in the text.
     */
    private FormatMatch findNextFormat(String text) {
        FormatMatch earliest = null;
        
        // Check for links
        Matcher linkMatcher = LINK_PATTERN.matcher(text);
        if (linkMatcher.find()) {
            earliest = new FormatMatch(FormatType.LINK, linkMatcher.start(), linkMatcher.end(), 
                                     linkMatcher.group(1), linkMatcher.group(2));
        }
        
        // Check for bold
        Matcher boldMatcher = BOLD_PATTERN.matcher(text);
        if (boldMatcher.find() && (earliest == null || boldMatcher.start() < earliest.start)) {
            earliest = new FormatMatch(FormatType.BOLD, boldMatcher.start(), boldMatcher.end(), 
                                     boldMatcher.group(1), null);
        }
        
        // Check for italic (but not inside bold)
        Matcher italicMatcher = ITALIC_PATTERN.matcher(text);
        if (italicMatcher.find() && (earliest == null || italicMatcher.start() < earliest.start)) {
            // Make sure it's not part of a bold pattern
            if (!text.substring(Math.max(0, italicMatcher.start() - 1), 
                              Math.min(text.length(), italicMatcher.end() + 1)).contains("**")) {
                earliest = new FormatMatch(FormatType.ITALIC, italicMatcher.start(), italicMatcher.end(), 
                                         italicMatcher.group(1), null);
            }
        }
        
        // Check for inline code
        Matcher codeMatcher = INLINE_CODE_PATTERN.matcher(text);
        if (codeMatcher.find() && (earliest == null || codeMatcher.start() < earliest.start)) {
            earliest = new FormatMatch(FormatType.INLINE_CODE, codeMatcher.start(), codeMatcher.end(), 
                                     codeMatcher.group(1), null);
        }
        
        return earliest;
    }
    
    /**
     * Render a formatted element.
     */
    private void renderFormattedElement(FormatMatch match) {
        switch (match.type) {
            case LINK -> renderLink(match.content, match.url);
            case BOLD -> {
                ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 1.0f, 1.0f, 1.0f);
                ImGui.text(match.content);
                ImGui.popStyleColor();
            }
            case ITALIC -> {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.9f, 0.9f, 0.9f, 1.0f);
                ImGui.text("/" + match.content + "/");
                ImGui.popStyleColor();
            }
            case INLINE_CODE -> renderInlineCode(match.content);
        }
    }
    
    /**
     * Render a clickable link.
     */
    private void renderLink(String linkText, String url) {
        ImGui.pushStyleColor(ImGuiCol.Button, 0.0f, 0.0f, 0.0f, 0.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, linkColorR * 0.3f, linkColorG * 0.3f, linkColorB * 0.3f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, linkColorR * 0.5f, linkColorG * 0.5f, linkColorB * 0.5f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.Text, linkColorR, linkColorG, linkColorB, 1.0f);
        
        if (ImGui.button(linkText)) {
            linkClickHandler.accept(url);
        }
        
        ImGui.popStyleColor(4);
        
        // Underline effect
        if (ImGui.isItemHovered()) {
            ImGui.getWindowDrawList().addLine(
                ImGui.getItemRectMinX(), ImGui.getItemRectMaxY(),
                ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY(),
                ImGui.colorConvertFloat4ToU32(linkColorR, linkColorG, linkColorB, 1.0f),
                1.0f
            );
        }
    }
    
    /**
     * Render inline code.
     */
    private void renderInlineCode(String code) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.2f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.Text, 0.9f, 0.9f, 0.9f, 1.0f);
        ImGui.textUnformatted(code);
        ImGui.popStyleColor(2);
    }
    
    /**
     * Handle default link clicks.
     */
    private void handleDefaultLinkClick(String url) {
        if (url.startsWith("topic:")) {
            String topicId = url.substring(6);
            logger.info("Navigate to topic: {}", topicId);
            // Topic navigation would be handled by the help browser
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            // Open external URL
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception e) {
                logger.warn("Failed to open URL: {}", url, e);
            }
        } else {
            logger.debug("Unhandled link: {}", url);
        }
    }
    
    // Configuration methods
    public void setHeaderScales(float h1, float h2, float h3) {
        this.headerScale1 = h1;
        this.headerScale2 = h2;
        this.headerScale3 = h3;
    }
    
    public void setLinkColor(float r, float g, float b) {
        this.linkColorR = r;
        this.linkColorG = g;
        this.linkColorB = b;
    }
    
    public void setCodeBlockPadding(float padding) {
        this.codeBlockPadding = padding;
    }
    
    public void setLinkClickHandler(Consumer<String> handler) {
        this.linkClickHandler = handler != null ? handler : this::handleDefaultLinkClick;
    }
    
    // Helper classes
    private static class FormatMatch {
        final FormatType type;
        final int start;
        final int end;
        final String content;
        final String url; // For links
        
        FormatMatch(FormatType type, int start, int end, String content, String url) {
            this.type = type;
            this.start = start;
            this.end = end;
            this.content = content;
            this.url = url;
        }
    }
    
    private enum FormatType {
        LINK, BOLD, ITALIC, INLINE_CODE
    }
}