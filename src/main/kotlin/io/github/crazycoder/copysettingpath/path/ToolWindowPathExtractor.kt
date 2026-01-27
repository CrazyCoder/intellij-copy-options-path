package io.github.crazycoder.copysettingpath.path

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import io.github.crazycoder.copysettingpath.appendItem
import java.awt.Component

/**
 * Extracts navigation paths from tool windows.
 *
 * This extractor handles path building for tool windows like Project, Terminal,
 * Run, Debug, Problems, etc. It uses the public IntelliJ Platform APIs to access
 * tool window information.
 *
 * Example paths produced:
 * - `Project | intellij-copy-options-path | src | main | kotlin`
 * - `Terminal | Local`
 * - `Run | MyApp | Console`
 * - `Debug | MyApp | Variables`
 */
object ToolWindowPathExtractor {

    /**
     * Builds the path for a component within a tool window.
     *
     * @param src The source UI component.
     * @param e The action event containing context information.
     * @param separator The separator to use between path components.
     * @return The built path string, or null if not in a tool window context.
     */
    fun buildPath(src: Component, e: AnActionEvent?, separator: String): String? {
        // Get tool window from data context (public API)
        val toolWindow = e?.getData(PlatformDataKeys.TOOL_WINDOW) ?: return null

        val path = StringBuilder()

        // 1. Add tool window name (stripe title)
        appendItem(path, toolWindow.stripeTitle, separator)

        // 2. Add selected content tab name if present and meaningful
        // Try tabName first, then displayName as fallback
        val selectedContent = toolWindow.contentManager.selectedContent
        val contentTabName = selectedContent?.tabName?.takeIf { it.isNotBlank() }
            ?: selectedContent?.displayName?.takeIf { it.isNotBlank() }
        if (!contentTabName.isNullOrBlank() && contentTabName != toolWindow.stripeTitle) {
            appendItem(path, contentTabName, separator)
        }

        // 3. Add middle path (tabs, titled borders) from component hierarchy
        SettingsPathExtractor.appendMiddlePath(src, null, path, separator)

        // 4. Add tree/table/list path if applicable
        TreeTablePathExtractor.appendPath(src, e, path, separator)

        // 5. Add component label
        ComponentLabelExtractor.appendComponentLabel(src, path)

        return if (path.isEmpty()) null else path.toString()
    }
}
