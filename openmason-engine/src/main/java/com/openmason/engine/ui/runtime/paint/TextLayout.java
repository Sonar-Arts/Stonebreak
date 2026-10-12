package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.ui.text.TextBoundaries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Line breaking and rich-text runs of a {@code Label} (feature {@code ui-text}): the one layout
 * that measurement, baselines and painting all read, so a wrapped label is exactly as tall as
 * the lines it paints.
 *
 * <p><b>White space.</b> {@code nowrap} (the default and the legacy rule) keeps the text as one
 * line exactly as given. {@code normal} collapses every run of spaces, tabs and line breaks to
 * one space and wraps at spaces. {@code pre-wrap} keeps spaces, breaks at {@code \n} and wraps at
 * spaces. Spaces at a wrap point are dropped (they "hang"). A word wider than the line breaks
 * between grapheme clusters, never inside one.
 *
 * <p><b>Overflow.</b> {@code -sb-max-lines > 0} keeps that many lines; {@code text-overflow:
 * ellipsis} then ends the last kept line with {@code "..."} (also for a {@code nowrap} line
 * wider than its box). Without ellipsis the extra lines are simply cut.
 *
 * <p><b>Rich text</b> ({@code rich: true}): {@code [color=#RRGGBB]} / {@code [color=#RRGGBBAA]},
 * {@code [b]}, {@code [i]}, {@code [u]} and their closing tags; tags nest. Anything else in
 * brackets, and a closing tag with nothing open, is literal text; {@code \[} is a literal
 * bracket and {@code \\} a literal backslash.
 */
public final class TextLayout {

    /** Slack in device pixels a line may exceed its width by: layout rounds boxes to the pixel grid. */
    static final float EPSILON = 1f;
    static final String ELLIPSIS = "...";

    public enum WhiteSpace {
        NOWRAP, NORMAL, PRE_WRAP;

        public static WhiteSpace of(String keyword) {
            return switch (keyword == null ? "" : keyword) {
                case "normal" -> NORMAL;
                case "pre-wrap" -> PRE_WRAP;
                default -> NOWRAP;
            };
        }
    }

    /**
     * A styled stretch of source text.
     *
     * @param color ARGB, meaningful only when {@code hasColor}; otherwise the element's colour
     */
    public record Span(String text, boolean hasColor, int color, boolean bold, boolean italic, boolean underline) {

        static final Span PLAIN = new Span("", false, 0, false, false, false);

        Span withText(String t) {
            return new Span(t, hasColor, color, bold, italic, underline);
        }

        boolean sameStyle(Span o) {
            return hasColor == o.hasColor && color == o.color && bold == o.bold && italic == o.italic
                && underline == o.underline;
        }
    }

    /** One drawn piece of a line: {@code x} from the line's left edge, device pixels. */
    public record Run(Span style, String text, float x, float width) {
    }

    public record Line(List<Run> runs, float width) {
    }

    /** @param width the widest line */
    public record Result(List<Line> lines, float width, boolean truncated) {
        public int lineCount() {
            return lines.size();
        }
    }

    /** Advance width of {@code text} drawn in {@code style}. */
    @FunctionalInterface
    public interface Measure {
        float width(String text, Span style);
    }

    private TextLayout() {
    }

    // ── markup ──────────────────────────────────────────────────────────────

    /** {@code text} as styled spans; plain text is one span. */
    public static List<Span> parse(String text, boolean rich) {
        if (!rich) {
            return List.of(Span.PLAIN.withText(text));
        }
        List<Span> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        List<Integer> colors = new ArrayList<>();
        int bold = 0;
        int italic = 0;
        int underline = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && (text.charAt(i + 1) == '[' || text.charAt(i + 1) == '\\')) {
                sb.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '[') {
                int end = text.indexOf(']', i + 1);
                if (end > i) {
                    String tag = text.substring(i + 1, end).toLowerCase(Locale.ROOT);
                    int[] next = {bold, italic, underline};
                    boolean known = true;
                    switch (tag) {
                        case "b" -> next[0]++;
                        case "i" -> next[1]++;
                        case "u" -> next[2]++;
                        case "/b" -> known = bold > 0 && next[0]-- > 0;
                        case "/i" -> known = italic > 0 && next[1]-- > 0;
                        case "/u" -> known = underline > 0 && next[2]-- > 0;
                        case "/color" -> known = !colors.isEmpty();
                        default -> known = tag.startsWith("color=") && color(tag.substring(6)) != null;
                    }
                    if (known) {
                        flush(out, sb, colors, bold, italic, underline);
                        if (tag.equals("/color")) {
                            colors.removeLast();
                        } else if (tag.startsWith("color=")) {
                            colors.add(color(tag.substring(6)));
                        }
                        bold = next[0];
                        italic = next[1];
                        underline = next[2];
                        i = end + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        flush(out, sb, colors, bold, italic, underline);
        return out.isEmpty() ? List.of(Span.PLAIN.withText("")) : out;
    }

    private static void flush(List<Span> out, StringBuilder sb, List<Integer> colors, int bold, int italic,
                              int underline) {
        if (sb.isEmpty()) {
            return;
        }
        boolean has = !colors.isEmpty();
        out.add(new Span(sb.toString(), has, has ? colors.getLast() : 0, bold > 0, italic > 0, underline > 0));
        sb.setLength(0);
    }

    /** {@code #rrggbb} or {@code #rrggbbaa} as ARGB, or null. */
    static Integer color(String v) {
        if (!v.startsWith("#") || (v.length() != 7 && v.length() != 9)) {
            return null;
        }
        try {
            long rgb = Long.parseLong(v.substring(1, 7), 16);
            long a = v.length() == 9 ? Long.parseLong(v.substring(7, 9), 16) : 0xFF;
            return (int) ((a << 24) | rgb);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── layout ──────────────────────────────────────────────────────────────

    /** A word: contiguous non-space segments, possibly in several styles. */
    private record Seg(Span style, String text) {
    }

    /**
     * Lays {@code spans} out in lines no wider than {@code maxWidth} (infinite = never wrap).
     *
     * @param maxLines 0 = unlimited
     */
    public static Result layout(List<Span> spans, WhiteSpace ws, float maxWidth, int maxLines, boolean ellipsis,
                                Measure m) {
        float limit = Float.isFinite(maxWidth) ? Math.max(0, maxWidth) + EPSILON : Float.POSITIVE_INFINITY;
        List<List<Seg>> lines = new ArrayList<>();
        if (ws == WhiteSpace.NOWRAP) {
            List<Seg> one = new ArrayList<>();
            for (Span s : spans) {
                if (!s.text().isEmpty()) {
                    one.add(new Seg(s, s.text()));
                }
            }
            lines.add(one);
        } else {
            breakLines(normalize(spans, ws), ws, limit, m, lines);
        }
        boolean truncated = false;
        if (maxLines > 0 && lines.size() > maxLines) {
            lines = new ArrayList<>(lines.subList(0, maxLines));
            truncated = true;
        }
        if (ellipsis && !lines.isEmpty()) {
            List<Seg> last = lines.getLast();
            if (truncated || width(last, m) > limit) {
                lines.set(lines.size() - 1, ellipsize(last, limit, m));
                truncated = true;
            }
        }
        List<Line> out = new ArrayList<>(lines.size());
        float widest = 0;
        for (List<Seg> segs : lines) {
            Line line = toLine(segs, m);
            out.add(line);
            widest = Math.max(widest, line.width());
        }
        return new Result(List.copyOf(out), widest, truncated);
    }

    /** Collapses ({@code normal}) or keeps ({@code pre-wrap}) white space; tabs become spaces. */
    private static List<Span> normalize(List<Span> spans, WhiteSpace ws) {
        List<Span> out = new ArrayList<>(spans.size());
        boolean lastSpace = true; // drops leading white space in normal mode
        for (Span s : spans) {
            StringBuilder sb = new StringBuilder(s.text().length());
            for (int i = 0; i < s.text().length(); i++) {
                char c = s.text().charAt(i);
                if (c == '\r') {
                    continue;
                }
                if (ws == WhiteSpace.NORMAL) {
                    if (c == ' ' || c == '\t' || c == '\n') {
                        if (!lastSpace) {
                            sb.append(' ');
                        }
                        lastSpace = true;
                    } else {
                        sb.append(c);
                        lastSpace = false;
                    }
                } else {
                    sb.append(c == '\t' ? "    " : String.valueOf(c));
                }
            }
            out.add(s.withText(sb.toString()));
        }
        return out;
    }

    private enum Kind { WORD, SPACE, BREAK }

    private record Token(Kind kind, List<Seg> segs) {
    }

    /** Words (possibly spanning styles), single spaces and hard breaks, in order. */
    private static List<Token> tokens(List<Span> spans) {
        List<Token> out = new ArrayList<>();
        List<Seg> word = new ArrayList<>();
        for (Span s : spans) {
            String t = s.text();
            int start = 0;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c != ' ' && c != '\n') {
                    continue;
                }
                if (i > start) {
                    word.add(new Seg(s, t.substring(start, i)));
                }
                if (!word.isEmpty()) {
                    out.add(new Token(Kind.WORD, word));
                    word = new ArrayList<>();
                }
                out.add(c == ' ' ? new Token(Kind.SPACE, List.of(new Seg(s, " "))) : new Token(Kind.BREAK, List.of()));
                start = i + 1;
            }
            if (start < t.length()) {
                word.add(new Seg(s, t.substring(start))); // the word may continue in the next span
            }
        }
        if (!word.isEmpty()) {
            out.add(new Token(Kind.WORD, word));
        }
        return out;
    }

    private static void breakLines(List<Span> spans, WhiteSpace ws, float limit, Measure m, List<List<Seg>> lines) {
        List<Seg> line = new ArrayList<>();
        float lineWidth = 0;
        List<Seg> pending = new ArrayList<>();
        for (Token tok : tokens(spans)) {
            switch (tok.kind()) {
                case SPACE -> {
                    if (!line.isEmpty() || ws == WhiteSpace.PRE_WRAP) {
                        pending.addAll(tok.segs());
                    }
                }
                case BREAK -> {
                    if (line.isEmpty()) {
                        line.addAll(pending); // a pre-wrap line of only spaces keeps them
                    }
                    pending.clear();
                    lines.add(line);
                    line = new ArrayList<>();
                    lineWidth = 0;
                }
                case WORD -> {
                    float ww = width(tok.segs(), m);
                    float sw = width(pending, m);
                    if (!line.isEmpty() && lineWidth + sw + ww > limit) {
                        lines.add(line); // the spaces at the wrap point hang and are dropped
                        line = new ArrayList<>();
                        lineWidth = 0;
                        pending.clear();
                        sw = 0;
                    }
                    line.addAll(pending);
                    lineWidth += sw;
                    pending.clear();
                    if (lineWidth + ww > limit) {
                        lineWidth = breakWord(tok.segs(), limit, m, lines, line);
                        line = lines.removeLast();
                    } else {
                        line.addAll(tok.segs());
                        lineWidth += ww;
                    }
                }
            }
        }
        if (line.isEmpty()) {
            line.addAll(pending);
        }
        lines.add(line);
    }

    /**
     * Breaks an over-wide word between grapheme clusters, continuing {@code line}: full lines go
     * to {@code lines} and the unfinished last line is appended last (the caller continues it).
     * A line always takes at least one cluster, so one cluster wider than the line sits alone.
     *
     * @return the unfinished line's width
     */
    private static float breakWord(List<Seg> word, float limit, Measure m, List<List<Seg>> lines, List<Seg> line) {
        List<Seg> current = line;
        float used = width(current, m);
        boolean hasContent = false; // of this word on the current line
        for (Seg seg : word) {
            String t = seg.text();
            int from = 0;
            int at = 0;
            while (at < t.length()) {
                int next = TextBoundaries.next(t, at);
                float w = m.width(t.substring(at, next), seg.style());
                if (used + w > limit && hasContent) {
                    if (at > from) {
                        current.add(new Seg(seg.style(), t.substring(from, at)));
                    }
                    lines.add(current);
                    current = new ArrayList<>();
                    used = 0;
                    from = at;
                }
                used += w;
                hasContent = true;
                at = next;
            }
            if (at > from) {
                current.add(new Seg(seg.style(), t.substring(from, at)));
            }
        }
        lines.add(current);
        return used;
    }

    /** {@code line} shortened by clusters until it fits with {@code "..."} appended. */
    private static List<Seg> ellipsize(List<Seg> line, float limit, Measure m) {
        List<Seg> segs = new ArrayList<>(line);
        while (!segs.isEmpty() && segs.getLast().text().isBlank()) {
            segs.removeLast();
        }
        Span style = segs.isEmpty() ? (line.isEmpty() ? Span.PLAIN : line.getLast().style()) : segs.getLast().style();
        while (true) {
            List<Seg> candidate = new ArrayList<>(segs);
            candidate.add(new Seg(style, ELLIPSIS));
            if (segs.isEmpty() || width(candidate, m) <= limit) {
                return candidate;
            }
            Seg last = segs.removeLast();
            String t = last.text();
            int cut = TextBoundaries.previous(t, t.length());
            String kept = t.substring(0, Math.max(0, cut)).stripTrailing();
            if (!kept.isEmpty()) {
                segs.add(new Seg(last.style(), kept));
                style = last.style();
            }
        }
    }

    private static float width(List<Seg> segs, Measure m) {
        float w = 0;
        for (Seg s : segs) {
            w += m.width(s.text(), s.style());
        }
        return w;
    }

    /** Merges same-style neighbours into runs and positions them. */
    private static Line toLine(List<Seg> segs, Measure m) {
        List<Run> runs = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        Span style = null;
        float x = 0;
        for (Seg s : segs) {
            if (style != null && !style.sameStyle(s.style())) {
                float w = m.width(sb.toString(), style);
                runs.add(new Run(style, sb.toString(), x, w));
                x += w;
                sb.setLength(0);
            }
            style = s.style();
            sb.append(s.text());
        }
        if (style != null && !sb.isEmpty()) {
            float w = m.width(sb.toString(), style);
            runs.add(new Run(style, sb.toString(), x, w));
            x += w;
        }
        return new Line(List.copyOf(runs), x);
    }
}
