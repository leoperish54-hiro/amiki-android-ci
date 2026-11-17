package com.amiki.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class MarkdownMessageRendererTest {
    @Test public void nullInputIsSafe() {
        assertEquals("", MarkdownMessageRenderer.boundedInput(null));
    }

    @Test public void shortInputIsUnchanged() {
        String markdown = "# Heading\n\n**bold** and `code`";
        assertEquals(markdown, MarkdownMessageRenderer.boundedInput(markdown));
    }

    @Test public void longInputIsBoundedAndMarked() {
        String markdown = new String(new char[9000]).replace('\0', 'x');
        String bounded = MarkdownMessageRenderer.boundedInput(markdown);
        assertTrue(bounded.length() < markdown.length());
        assertTrue(bounded.startsWith(markdown.substring(0, 8000)));
        assertTrue(bounded.contains("shortened for display"));
    }
}
