package com.openmason.main.systems.assistant.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMarkdownTest {

    @Test
    void paragraphsSplitOnBlankLines() {
        List<ChatMarkdown.Block> blocks = ChatMarkdown.parse("one\ntwo\n\nthree");
        assertEquals(2, blocks.size());
        assertInstanceOf(ChatMarkdown.Block.Paragraph.class, blocks.get(0));
        ChatMarkdown.Block.Paragraph first = (ChatMarkdown.Block.Paragraph) blocks.get(0);
        assertEquals("one\ntwo", first.spans().get(0).text());
    }

    @Test
    void headingsAndBullets() {
        List<ChatMarkdown.Block> blocks = ChatMarkdown.parse(
                "## Plan\n- first step\n- second **bold** step\n2. numbered");
        assertEquals(4, blocks.size());
        ChatMarkdown.Block.Heading heading = (ChatMarkdown.Block.Heading) blocks.get(0);
        assertEquals(2, heading.level());
        assertEquals("Plan", heading.spans().get(0).text());
        ChatMarkdown.Block.Bullet bullet = (ChatMarkdown.Block.Bullet) blocks.get(1);
        assertEquals("•", bullet.marker());
        ChatMarkdown.Block.Bullet boldBullet = (ChatMarkdown.Block.Bullet) blocks.get(2);
        assertTrue(boldBullet.spans().stream().anyMatch(s -> s.bold() && s.text().equals("bold")));
        ChatMarkdown.Block.Bullet numbered = (ChatMarkdown.Block.Bullet) blocks.get(3);
        assertEquals("2.", numbered.marker());
    }

    @Test
    void inlineSpans() {
        List<ChatMarkdown.Span> spans =
                ChatMarkdown.parseSpans("use `model_summary` then **inspect** things");
        assertEquals(5, spans.size());
        assertEquals("use ", spans.get(0).text());
        assertTrue(spans.get(1).code());
        assertEquals("model_summary", spans.get(1).text());
        assertEquals(" then ", spans.get(2).text());
        assertTrue(spans.get(3).bold());
        assertEquals("inspect", spans.get(3).text());
        assertEquals(" things", spans.get(4).text());
    }

    @Test
    void unmatchedMarkersStayLiteral() {
        List<ChatMarkdown.Span> spans = ChatMarkdown.parseSpans("a ** b ` c");
        assertEquals(1, spans.size());
        assertEquals("a ** b ` c", spans.get(0).text());
    }

    @Test
    void fencedCodeWithLanguage() {
        List<ChatMarkdown.Block> blocks = ChatMarkdown.parse(
                "before\n```python\nprint('hi')\n```\nafter");
        assertEquals(3, blocks.size());
        ChatMarkdown.Block.CodeBlock code = (ChatMarkdown.Block.CodeBlock) blocks.get(1);
        assertEquals("python", code.language());
        assertEquals("print('hi')", code.body());
    }

    @Test
    void unterminatedFenceStillCode() {
        List<ChatMarkdown.Block> blocks = ChatMarkdown.parse("text\n```\ncode line");
        assertTrue(blocks.stream().anyMatch(b -> b instanceof ChatMarkdown.Block.CodeBlock));
    }

    @Test
    void plainTextProjectionRoundTrips() {
        String text = "## H\n- item\n\npara **bold**\n```\ncode\n```";
        String plain = ChatMarkdown.toPlainText(ChatMarkdown.parse(text));
        assertTrue(plain.contains("H"));
        assertTrue(plain.contains("• item"));
        assertTrue(plain.contains("bold"));
        assertTrue(plain.contains("code"));
    }

    @Test
    void emptyAndNullSafe() {
        assertTrue(ChatMarkdown.parse(null).isEmpty());
        assertTrue(ChatMarkdown.parse("   \n  ").isEmpty());
    }
}
