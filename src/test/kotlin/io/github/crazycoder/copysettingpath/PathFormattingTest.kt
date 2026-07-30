package io.github.crazycoder.copysettingpath

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure text handling of path building, which needs no IDE to exercise.
 */
class PathFormattingTest {

    private val pipe = PathSeparator.PIPE.separator
    private val arrow = PathSeparator.ARROW.separator

    private fun path(vararg items: String, separator: String = pipe): String {
        val builder = StringBuilder()
        items.forEach { appendItem(builder, it, separator) }
        return trimFinalResult(builder, separator)
    }

    @Test
    fun `joins items with the separator`() {
        assertEquals("Editor | Code Style | Java", path("Editor", "Code Style", "Java"))
    }

    @Test
    fun `honours a non-default separator`() {
        assertEquals("Editor > Code Style", path("Editor", "Code Style", separator = arrow))
    }

    /**
     * Trimming used to remove any character used by any separator style, so a value ending in
     * one of them lost it. These are the cases that regressed.
     */
    @Test
    fun `keeps punctuation that belongs to a value`() {
        assertEquals("Scheme | <default>", path("Scheme", "<default>"))
        assertEquals("Type | List<String>", path("Type", "List<String>"))
        assertEquals("Quote | Guillemet »", path("Quote", "Guillemet »"))
    }

    @Test
    fun `keeps a value ending in the separator character when the separator differs`() {
        assertEquals("Type > List<String>", path("Type", "List<String>", separator = arrow))
    }

    @Test
    fun `drops a repeated trailing segment`() {
        assertEquals("Editor | Java", path("Editor", "Java", "Java"))
    }

    @Test
    fun `keeps a repeated segment that is not adjacent`() {
        assertEquals("Java | Editor | Java", path("Java", "Editor", "Java"))
    }

    /**
     * A label ending in a colon groups with the value that follows it, joined by a space
     * rather than by the separator.
     */
    @Test
    fun `groups a colon label with its value`() {
        assertEquals("Java | Logger: Unspecified", path("Java", "Logger:", "Unspecified"))
    }

    @Test
    fun `keeps a trailing colon label with no value`() {
        assertEquals("Java | Logger:", path("Java", "Logger:"))
    }

    /**
     * Advanced Settings appends the setting id straight after the colon, with no space, and it
     * is stripped from the finished path. A real value is separated from its label by a space,
     * which is what keeps a dotted value such as a package name intact.
     */
    @Test
    fun `strips a trailing setting id but keeps a dotted value`() {
        assertEquals("Java | Logger: com.example.app", path("Java", "Logger:", "com.example.app"))
        assertEquals("Mouse click interception", trimFinalResult(
            StringBuilder("Mouse click interception:copy.setting.path.mouse.intercept"), pipe
        ))
    }

    @Test
    fun `strips html markup and collapses whitespace`() {
        assertEquals("Bold label", "<html><b>Bold</b>\n   label</html>".removeHtmlTags())
    }

    /**
     * Swing renders a label as HTML only when the text starts with the html tag. Without it the
     * angle brackets are part of the value, and settings use them constantly.
     */
    @Test
    fun `keeps angle brackets in text that is not html`() {
        assertEquals("<default>", "<default>".removeHtmlTags())
        assertEquals("<no scheme>", "<no scheme>".removeHtmlTags())
        assertEquals("List<String>", "List<String>".removeHtmlTags())
        assertEquals("Use <Project> level", "Use <Project> level".removeHtmlTags())
    }

    /**
     * Advanced Settings renders the setting id after a line break inside a pre block.
     * Ordinary multi-line labels must keep their remaining lines.
     */
    @Test
    fun `strips the advanced settings id but keeps ordinary line breaks`() {
        assertEquals(
            "Label text",
            "<html>Label text<br><pre><font>copy.setting.path.separator</font></pre></html>".removeHtmlTags()
        )
        assertEquals(
            "First line second line",
            "<html>First line<br>second line</html>".removeHtmlTags()
        )
    }

    @Test
    fun `every separator style is distinct and padded`() {
        val separators = PathSeparator.entries.map { it.separator }
        assertEquals(separators.size, separators.toSet().size)
        separators.forEach {
            assertEquals("Separator '$it' must be padded", " ", it.take(1))
            assertEquals("Separator '$it' must be padded", " ", it.takeLast(1))
        }
    }
}
