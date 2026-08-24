package com.openmason.main.systems.assistant.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, deterministic markdown parser for chat messages — the subset local
 * models actually emit: paragraphs, #/##/### headings, dash/star/numbered bullets,
 * ```fenced code blocks```, and inline **bold** / `code` spans. Pure and
 * unit-testable; renderers decide presentation.
 */
public final class ChatMarkdown {

    /** One styled run of text inside a block. */
    public record Span(String text, boolean bold, boolean code) {
    }

    /** A block-level element. */
    public sealed interface Block {
        record Paragraph(List<Span> spans) implements Block {
        }

        record Heading(int level, List<Span> spans) implements Block {
        }

        /** marker is the rendered prefix, e.g. "•" or "3." */
        record Bullet(int indent, String marker, List<Span> spans) implements Block {
        }

        record CodeBlock(String language, String body) implements Block {
        }
    }

    private ChatMarkdown() {
    }

    public static List<Block> parse(String text) {
        List<Block> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return blocks;
        }
        String[] fenceSplit = text.split("```", -1);
        for (int i = 0; i < fenceSplit.length; i++) {
            if (i % 2 == 1) {
                // Fenced code: first line may be a language tag.
                String segment = fenceSplit[i];
                String language = "";
                String body = segment;
                int nl = segment.indexOf('\n');
                if (nl >= 0) {
                    String first = segment.substring(0, nl).strip();
                    if (!first.isEmpty() && !first.contains(" ") && first.length() <= 16) {
                        language = first;
                        body = segment.substring(nl + 1);
                    }
                } else if (!segment.contains(" ")) {
                    // Unterminated single-line "fence" — treat as code anyway.
                    body = segment;
                }
                blocks.add(new Block.CodeBlock(language, stripTrailingNewlines(body)));
            } else {
                parseProse(fenceSplit[i], blocks);
            }
        }
        return blocks;
    }

    private static void parseProse(String prose, List<Block> blocks) {
        List<String> paragraphLines = new ArrayList<>();
        for (String rawLine : prose.split("\n", -1)) {
            String line = rawLine.stripTrailing();
            String stripped = line.stripLeading();
            if (stripped.isEmpty()) {
                flushParagraph(paragraphLines, blocks);
                continue;
            }
            int heading = headingLevel(stripped);
            if (heading > 0) {
                flushParagraph(paragraphLines, blocks);
                blocks.add(new Block.Heading(heading,
                        parseSpans(stripped.substring(heading).strip())));
                continue;
            }
            String bulletBody = bulletBody(stripped);
            if (bulletBody != null) {
                flushParagraph(paragraphLines, blocks);
                int indentChars = line.length() - stripped.length();
                String marker = stripped.startsWith("-") || stripped.startsWith("*")
                        || stripped.startsWith("+")
                        ? "•"
                        : stripped.substring(0, stripped.indexOf('.') + 1);
                blocks.add(new Block.Bullet(Math.min(3, indentChars / 2), marker,
                        parseSpans(bulletBody)));
                continue;
            }
            paragraphLines.add(stripped);
        }
        flushParagraph(paragraphLines, blocks);
    }

    private static void flushParagraph(List<String> lines, List<Block> blocks) {
        if (lines.isEmpty()) {
            return;
        }
        blocks.add(new Block.Paragraph(parseSpans(String.join("\n", lines))));
        lines.clear();
    }

    private static int headingLevel(String stripped) {
        int hashes = 0;
        while (hashes < stripped.length() && stripped.charAt(hashes) == '#') {
            hashes++;
        }
        if (hashes >= 1 && hashes <= 3 && hashes < stripped.length()
                && stripped.charAt(hashes) == ' ') {
            return hashes;
        }
        return 0;
    }

    private static String bulletBody(String stripped) {
        if ((stripped.startsWith("- ") || stripped.startsWith("* ") || stripped.startsWith("+ "))
                && stripped.length() > 2) {
            return stripped.substring(2).strip();
        }
        int dot = stripped.indexOf(". ");
        if (dot >= 1 && dot <= 3) {
            String digits = stripped.substring(0, dot);
            if (digits.chars().allMatch(Character::isDigit)) {
                return stripped.substring(dot + 2).strip();
            }
        }
        return null;
    }

    /** Inline parse: {@code **bold**} and {@code `code`}; unmatched markers stay literal. */
    static List<Span> parseSpans(String text) {
        List<Span> spans = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("**", i)) {
                int end = text.indexOf("**", i + 2);
                if (end > i + 2) {
                    flushPlain(plain, spans);
                    appendSpan(spans, text.substring(i + 2, end), true, false);
                    i = end + 2;
                    continue;
                }
            }
            if (text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    flushPlain(plain, spans);
                    appendSpan(spans, text.substring(i + 1, end), false, true);
                    i = end + 1;
                    continue;
                }
            }
            plain.append(text.charAt(i));
            i++;
        }
        flushPlain(plain, spans);
        return spans;
    }

    private static void flushPlain(StringBuilder plain, List<Span> spans) {
        if (plain.length() > 0) {
            appendSpan(spans, plain.toString(), false, false);
            plain.setLength(0);
        }
    }

    private static void appendSpan(List<Span> spans, String text, boolean bold, boolean code) {
        if (!text.isEmpty()) {
            spans.add(new Span(text, bold, code));
        }
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    /** Plain-text projection (clipboard for Skija-rendered prose). */
    public static String toPlainText(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Block block : blocks) {
            switch (block) {
                case Block.Paragraph p -> appendSpansText(sb, p.spans());
                case Block.Heading h -> appendSpansText(sb, h.spans());
                case Block.Bullet b -> {
                    sb.append("  ".repeat(b.indent())).append(b.marker()).append(' ');
                    appendSpansText(sb, b.spans());
                }
                case Block.CodeBlock c -> sb.append(c.body()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    private static void appendSpansText(StringBuilder sb, List<Span> spans) {
        for (Span span : spans) {
            sb.append(span.text());
        }
    }
}
