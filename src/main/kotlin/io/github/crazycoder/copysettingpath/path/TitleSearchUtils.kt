package io.github.crazycoder.copysettingpath.path

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import io.github.crazycoder.copysettingpath.removeHtmlTags
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

    /**
     * Searches for a JLabel with bold font.
     *
     * @param container The container to search in.
     * @param maxDepth Maximum depth to search into nested containers.
     * @return The bold label's text, or null if not found.
     */
    fun findBoldLabelText(container: Container, maxDepth: Int): String? {
        if (maxDepth <= 0) return null

        for (component in container.components) {
            if (component is JLabel) {
                val font = component.font
                if (font != null && font.isBold) {
                    val text = component.text?.removeHtmlTags()?.trim()
                    if (!text.isNullOrBlank()) {
                        return text
                    }
                }
            }
            if (component is Container) {
                val found = findBoldLabelText(component, maxDepth - 1)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    /**
     * Searches for a SimpleColoredComponent with BOLD text attributes.
     * This is common for popup titles in custom header panels.
     *
     * @param container The container to search in.
     * @param maxDepth Maximum depth to search into nested containers.
     * @return The bold text, or null if not found.
     */
    fun findBoldSimpleColoredText(container: Container, maxDepth: Int): String? {
        if (maxDepth <= 0) return null

        for (component in container.components) {
            if (component is SimpleColoredComponent) {
                val boldText = extractBoldText(component)
                if (!boldText.isNullOrBlank()) {
                    return boldText
                }
            }
            if (component is Container) {
                val found = findBoldSimpleColoredText(component, maxDepth - 1)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    /**
     * Finds a label that looks like a title (short text, doesn't end with ":").
     * This is a fallback when bold detection doesn't work.
     *
     * @param container The container to search in.
     * @param maxDepth Maximum depth to search into nested containers.
     * @return The title-like label text, or null if not found.
     */
    fun findTitleLikeLabel(container: Container, maxDepth: Int): String? {
        if (maxDepth <= 0) return null

        for (component in container.components) {
            if (component is JLabel) {
                val text = component.text?.removeHtmlTags()?.trim()
                // Title-like: not empty, doesn't end with ":", reasonably short, not a shortcut hint
                if (!text.isNullOrBlank() &&
                    !text.endsWith(":") &&
                    text.length in 3..50 &&
                    !text.contains("Ctrl+") &&
                    !text.contains("Cmd+") &&
                    !text.contains("Alt+")
                ) {
                    return text
                }
            }
            if (component is Container) {
                val found = findTitleLikeLabel(component, maxDepth - 1)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

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
