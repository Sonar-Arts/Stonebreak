package com.openmason.engine.ui.l10n;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageFormatterTest {

    private static final String ITEMS = "{count, plural, =0 {no items} one {# item} other {# items}}";

    private static String en(String pattern, Map<String, Object> args) {
        return MessageFormatter.format(pattern, args, Locale.ENGLISH);
    }

    @Test
    void plainArgumentsAndText() {
        assertEquals("Hello, Steve!", en("Hello, {name}!", Map.of("name", "Steve")));
        assertEquals("no args", en("no args", Map.of()));
        assertEquals("3 of 7", en("{a} of {b}", Map.of("a", 3, "b", 7.0)));
    }

    @Test
    void numbersFollowTheLocale() {
        assertEquals("1,234.5", en("{n}", Map.of("n", 1234.5)));
        assertEquals("1.234,5", MessageFormatter.format("{n}", Map.of("n", 1234.5), Locale.GERMAN));
        assertEquals("1,234", en("{n, number}", Map.of("n", 1234)));
        assertEquals("1,235", en("{n, number, integer}", Map.of("n", 1234.6)));
        assertEquals("50%", en("{n, number, percent}", Map.of("n", 0.5)));
        assertEquals("2", en("{n}", Map.of("n", 2.0)), "integral doubles show no fraction");
    }

    @Test
    void pluralWithPoundAndExactMatch() {
        assertEquals("no items", en(ITEMS, Map.of("count", 0)));
        assertEquals("1 item", en(ITEMS, Map.of("count", 1)));
        assertEquals("1,000 items", en(ITEMS, Map.of("count", 1000)));
        assertEquals("2 items", en(ITEMS, Map.of("count", "2")), "a numeric string still pluralises");
        assertEquals("? items", en(ITEMS, Map.of("count", "?")), "a non-number takes other");
    }

    @Test
    void pluralOffsetShiftsCategoryAndPoundButNotExactMatches() {
        String p = "{n, plural, offset:1 =0 {nobody} =1 {you} one {you and # other} other {you and # others}}";
        assertEquals("nobody", en(p, Map.of("n", 0)));
        assertEquals("you", en(p, Map.of("n", 1)));
        assertEquals("you and 1 other", en(p, Map.of("n", 2)));
        assertEquals("you and 4 others", en(p, Map.of("n", 5)));
    }

    @Test
    void pluralUsesTheLocalesRules() {
        String p = "{n, plural, one {# книга} few {# книги} many {# книг} other {# книги}}";
        Locale ru = Locale.forLanguageTag("ru");
        assertEquals("21 книга", MessageFormatter.format(p, Map.of("n", 21), ru));
        assertEquals("22 книги", MessageFormatter.format(p, Map.of("n", 22), ru));
        assertEquals("25 книг", MessageFormatter.format(p, Map.of("n", 25), ru));
    }

    @Test
    void selectNestsInsidePluralAndSeesThePound() {
        String p = "{n, plural, one {{g, select, female {her # cat} other {their # cat}}} "
            + "other {{g, select, female {her # cats} other {their # cats}}}}";
        assertEquals("her 1 cat", en(p, Map.of("n", 1, "g", "female")));
        assertEquals("their 3 cats", en(p, Map.of("n", 3, "g", "x")));
        assertEquals("their 3 cats", en(p, Map.of("n", 3)), "a missing select argument takes other");
        assertEquals(Set.of("n", "g"), MessagePattern.compile(p).argumentNames());
    }

    @Test
    void apostropheQuoting() {
        assertEquals("it's", en("it's", Map.of()), "a lone apostrophe is literal");
        assertEquals("it's", en("it''s", Map.of()));
        assertEquals("{name}", en("'{name}'", Map.of("name", "x")));
        assertEquals("a {b} c", en("a '{b}' c", Map.of()));
        assertEquals("# is 1 and it's '{}'", en("{n, plural, other {'#' is # and it''s '''{}'''}}", Map.of("n", 1)));
        assertEquals("#1", en("#{n}", Map.of("n", 1)), "# outside a plural is text");
    }

    @Test
    void missingArgumentsRenderTheirName() {
        assertEquals("Hello, {name}!", en("Hello, {name}!", Map.of()));
        assertEquals("{count} items", en("{count} items", null));
        assertEquals("{count}", en("{count, number}", Map.of()));
    }

    @Test
    void syntaxErrorsCarryPositions() {
        assertEquals(5, error("abc {").position());
        assertEquals(4, error("abc }").position());
        MessageFormatException noOther = error("{n, plural, one {x}}");
        assertTrue(noOther.getMessage().contains("other"), noOther.getMessage());
        assertEquals(12, noOther.position());
        assertTrue(error("{n, select, a {x}}").getMessage().contains("other"));
        assertEquals(4, error("{n, ordinalish}").position());
        assertEquals(12, error("{n, plural, lots {x} other {y}}").position());
        assertEquals(20, error("{n, plural, one {x} one {y} other {z}}").position());
        assertEquals(1, error("{}").position());
        assertTrue(error("'{unterminated").getMessage().contains("quote"));
        assertEquals(12, error("{n, number, currency}").position());
    }

    private static MessageFormatException error(String pattern) {
        return assertThrows(MessageFormatException.class, () -> MessagePattern.compile(pattern));
    }
}
