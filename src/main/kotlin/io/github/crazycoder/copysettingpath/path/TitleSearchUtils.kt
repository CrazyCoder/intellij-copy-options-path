package io.github.crazycoder.copysettingpath.path

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import io.github.crazycoder.copysettingpath.descendants
import io.github.crazycoder.copysettingpath.removeHtmlTags
import io.github.crazycoder.copysettingpath.visibleText
import java.awt.Container
import javax.swing.JLabel

/**
 * Utility object for searching title-like components in UI hierarchies.
 *
 * This consolidates common title extraction logic used by PathBuilder and PopupPathExtractor.
 * It provides methods to find:
 * - Bold JLabels (common for dialog headers)
 * - SimpleColoredComponents with bold text
 * - Title-like labels (short text, doesn't end with ":")
 */
object TitleSearchUtils {

    /** Longest text still plausible as a title rather than a sentence. */
    private const val MAX_TITLE_LENGTH = 50

    /** Shortest text still plausible as a title rather than a mnemonic or an icon caption. */
    private const val MIN_TITLE_LENGTH = 3

    /** Shortcut hints look like titles but are not. */
    private val SHORTCUT_HINTS = listOf("Ctrl+", "Cmd+", "Alt+")

    /**
     * Searches for a JLabel with bold font.
     *
     * @param container The container to search in.
     * @param maxDepth Maximum depth to search into nested containers.
     * @return The bold label's text, or null if not found.
     */
    fun findBoldLabelText(container: Container, maxDepth: Int): String? =
        descendants(container, maxDepth)
            .filterIsInstance<JLabel>()
            .filter { it.font?.isBold == true }
            .firstNotNullOfOrNull { it.visibleText() }

    /**
     * Searches for a SimpleColoredComponent with BOLD text attributes.
     * This is common for popup titles in custom header panels.
     *
     * @param container The container to search in.
     * @param maxDepth Maximum depth to search into nested containers.
     * @return The bold text, or null if not found.
     */
    fun findBoldSimpleColoredText(container: Container, maxDepth: Int): String? =
        descendants(container, maxDepth)
            .filterIsInstance<SimpleColoredComponent>()
            .firstNotNullOfOrNull { extractBoldText(it) }

    /**
     * Finds a label that looks like a title (short text, doesn't end with ":").
     * This is a fallback when bold detection doesn't work.
     *
     * @param container The container to search in.
     * @param maxDepth Maximum depth to search into nested containers.
     * @return The title-like label text, or null if not found.
     */
    fun findTitleLikeLabel(container: Container, maxDepth: Int): String? =
        descendants(container, maxDepth)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.visibleText() }
            .firstOrNull { it.looksLikeTitle() }

    /**
     * Title-like: reasonably short, not a field label, not a shortcut hint.
     */
    private fun String.looksLikeTitle(): Boolean =
        !endsWith(":") &&
                length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH &&
                SHORTCUT_HINTS.none { contains(it) }

    /**
     * Extracts bold text from a SimpleColoredComponent.
     * Returns the concatenated text of all BOLD fragments.
     *
     * @param component The SimpleColoredComponent to extract from.
     * @return The concatenated bold text, or null if none found.
     */
    fun extractBoldText(component: SimpleColoredComponent): String? {
        return runCatching {
            val iterator = component.iterator()
            val boldParts = mutableListOf<String>()

            while (iterator.hasNext()) {
                val fragment = iterator.next()
                val text = fragment?.removeHtmlTags()?.trim()
                if (!text.isNullOrBlank()) {
                    // Check if this fragment has BOLD style
                    val style = iterator.textAttributes.style
                    if ((style and SimpleTextAttributes.STYLE_BOLD) != 0) {
                        boldParts.add(text)
                    }
                }
            }

            boldParts.joinToString(" ").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
