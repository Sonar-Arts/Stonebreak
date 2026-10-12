package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.ui.runtime.paint.TextLayout.Line;
import com.openmason.engine.ui.runtime.paint.TextLayout.Result;
import com.openmason.engine.ui.runtime.paint.TextLayout.Span;
import com.openmason.engine.ui.runtime.paint.TextLayout.WhiteSpace;
import com.openmason.engine.ui.text.TextBoundaries;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Line breaking, truncation and rich-text markup of wrapped labels (ui-text, C3). */
class TextLayoutTest {

    /** 10 px per grapheme cluster, bold or not. */
    private static final TextLayout.Measure MONO = (text, span) -> 10f * TextBoundaries.count(text);

    private static Result lay(String text, WhiteSpace ws, float width) {
        return TextLayout.layout(TextLayout.parse(text, false), ws, width, 0, false, MONO);
    }

    private static List<String> lines(Result r) {
        return r.lines().stream().map(TextLayoutTest::text).collect(Collectors.toList());
    }

    private static String text(Line l) {
        return l.runs().stream().map(TextLayout.Run::text).collect(Collectors.joining());
    }

    @Test
    void nowrapKeepsTheLegacySingleLineExactlyAsGiven() {
        Result r = lay("  two  spaces\nand a break", WhiteSpace.NOWRAP, 50);
        assertEquals(List.of("  two  spaces\nand a break"), lines(r));
        assertFalse(r.truncated());
    }

    @Test
    void normalCollapsesWhiteSpaceAndWrapsAtSpaces() {
        Result r = lay("  the quick\n\tbrown   fox jumps ", WhiteSpace.NORMAL, 100);
        assertEquals(List.of("the quick", "brown fox", "jumps"), lines(r));
        assertEquals(90, r.width(), 1e-3, "widest line");
        for (Line l : r.lines()) {
            assertTrue(l.width() <= 100 + TextLayout.EPSILON);
        }
    }

    @Test
    void unconstrainedWidthIsMaxContent() {
        Result r = lay("the quick brown fox", WhiteSpace.NORMAL, Float.POSITIVE_INFINITY);
        assertEquals(List.of("the quick brown fox"), lines(r));
        assertEquals(190, r.width(), 1e-3);
    }

    @Test
    void preWrapKeepsSpacesAndHardBreaks() {
        Result r = lay("a  b\n\n  c", WhiteSpace.PRE_WRAP, 1000);
        assertEquals(List.of("a  b", "", "  c"), lines(r));
    }

    @Test
    void anOverWideWordBreaksBetweenClustersNeverInsideOne() {
        String family = "👨‍👩‍👧"; // one ZWJ cluster
        Result r = lay("abcdef" + family + "gh", WhiteSpace.NORMAL, 30);
        List<String> l = lines(r);
        assertEquals(List.of("abc", "def", family + "gh"), l);
        for (String s : l) {
            assertEquals(s.length(), TextBoundaries.snap(s, s.length()), "cut on a cluster boundary");
        }
    }

    @Test
    void aClusterWiderThanTheLineSitsAloneInsteadOfLooping() {
        Result r = lay("abc", WhiteSpace.NORMAL, 4);
        assertEquals(List.of("a", "b", "c"), lines(r));
    }

    @Test
    void maxLinesWithEllipsisEndsTheLastKeptLine() {
        Result r = TextLayout.layout(TextLayout.parse("one two three four five", false), WhiteSpace.NORMAL, 90, 2,
            true, MONO);
        assertEquals(2, r.lineCount());
        assertTrue(r.truncated());
        assertTrue(lines(r).get(1).endsWith(TextLayout.ELLIPSIS), lines(r).toString());
        assertTrue(r.lines().get(1).width() <= 90 + TextLayout.EPSILON);
    }

    @Test
    void maxLinesWithoutEllipsisCuts() {
        Result r = TextLayout.layout(TextLayout.parse("one two three four", false), WhiteSpace.NORMAL, 70, 1,
            false, MONO);
        assertEquals(List.of("one two"), lines(r));
        assertTrue(r.truncated());
    }

    @Test
    void nowrapEllipsisTruncatesToTheBox() {
        Result r = TextLayout.layout(TextLayout.parse("Resume Game", false), WhiteSpace.NOWRAP, 60, 0, true, MONO);
        assertEquals(List.of("Res..."), lines(r));
        Result fits = TextLayout.layout(TextLayout.parse("Resume", false), WhiteSpace.NOWRAP, 60, 0, true, MONO);
        assertEquals(List.of("Resume"), lines(fits));
        assertFalse(fits.truncated());
    }

    @Test
    void richMarkupNestsAndUnknownTagsStayLiteral() {
        List<Span> spans = TextLayout.parse("a[b]b[color=#FF0000]c[/color][/b][x]d\\[e][/i]", true);
        assertEquals("a", spans.get(0).text());
        assertFalse(spans.get(0).bold());
        assertEquals("b", spans.get(1).text());
        assertTrue(spans.get(1).bold());
        assertEquals("c", spans.get(2).text());
        assertTrue(spans.get(2).bold() && spans.get(2).hasColor());
        assertEquals(0xFFFF0000, spans.get(2).color());
        assertEquals("[x]d[e][/i]", spans.get(3).text(), "unknown tag, escape and unmatched closer are text");
        assertFalse(spans.get(3).bold());
    }

    @Test
    void richColourAlphaAndInvalidColours() {
        assertEquals(0x80112233, TextLayout.parse("[color=#11223380]x", true).getFirst().color());
        assertEquals("[color=red]x", TextLayout.parse("[color=red]x", true).getFirst().text());
    }

    @Test
    void wrappedRichTextKeepsStylesAcrossLinesAndMergesRuns() {
        Result r = TextLayout.layout(TextLayout.parse("ab [b]cd ef[/b] gh", true), WhiteSpace.NORMAL, 50, 0, false,
            MONO);
        assertEquals(List.of("ab cd", "ef gh"), lines(r));
        Line first = r.lines().getFirst();
        assertEquals(2, first.runs().size(), "plain 'ab ' then bold 'cd'");
        assertTrue(first.runs().get(1).style().bold());
        assertEquals(30, first.runs().get(1).x(), 1e-3);
        assertTrue(r.lines().get(1).runs().getFirst().style().bold());
    }

    @Test
    void aWordSplitAcrossStylesIsNotABreakOpportunity() {
        Result r = TextLayout.layout(TextLayout.parse("xx bo[b]ld[/b]", true), WhiteSpace.NORMAL, 50, 0, false, MONO);
        assertEquals(List.of("xx", "bold"), lines(r));
    }
}
