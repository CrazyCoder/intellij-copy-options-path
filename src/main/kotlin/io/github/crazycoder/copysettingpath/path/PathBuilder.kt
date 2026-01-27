package io.github.crazycoder.copysettingpath.path

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ex.SingleConfigurableEditor
import com.intellij.openapi.options.newEditor.SettingsDialog
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ComponentUtil
import io.github.crazycoder.copysettingpath.appendItem
import java.awt.Component
import javax.swing.JTree

/**
 * Main orchestration class for building setting paths.
 *
 * This class detects the context type and delegates to the appropriate handler:
 * - Settings dialog: Uses SettingsPathExtractor
 * - Project Structure dialog: Uses ProjectStructurePathHandler
 * - Generic dialogs: Uses GenericDialogPathHandler
 * - Tool windows: Uses ToolWindowPathExtractor
 *
 * The path building process follows IntelliJ's CopySettingsPathAction pattern:
 * 1. Get base path from SettingsEditor.getPathNames() or tool window name
 * 2. Walk up hierarchy for tabs and titled borders
 * 3. Add component label via labeledBy property
 */
object PathBuilder {

    /**
     * Builds the complete setting path for the given source component.
     *
     * @param src The source UI component.
     * @param e The action event containing context information.
     * @param separator The separator to use between path components.
     * @return The built path string, or null if path cannot be determined.
     */
    fun buildPath(src: Component, e: AnActionEvent?, separator: String): String? {
        // 1. Try dialog-based path (existing behavior)
        val dialog = DialogWrapper.findInstance(src)
        if (dialog != null) {
            return buildDialogPath(src, e, dialog, separator)
        }

        // 2. Try tool window path (new)
        val toolWindowPath = ToolWindowPathExtractor.buildPath(src, e, separator)
        if (toolWindowPath != null) {
            return toolWindowPath
        }

        // 3. No context found
        return null
    }

    /**
     * Builds the path for a component within a dialog.
     */
    private fun buildDialogPath(
        src: Component,
        e: AnActionEvent?,
        dialog: DialogWrapper,
        separator: String
    ): String? {
        val path = StringBuilder()

        // Build base path based on dialog type
        when (dialog) {
            is SettingsDialog -> SettingsPathExtractor.appendSettingsPath(src, path, separator)
            else -> appendGenericDialogPath(dialog, src, path, separator)
        }

        // Add tree/table/list path if applicable
        // Skip only for the main Settings tree (inside SettingsTreeView) - getPathNames() already includes it
        // But include for secondary trees within settings pages (like color scheme tree)
        if (!isMainSettingsTree(src)) {
            appendTreeOrTablePath(src, e, path, separator)
        }

        // Add source component label
        ComponentLabelExtractor.appendComponentLabel(src, path)

        return if (path.isEmpty()) null else path.toString()
    }

    /**
     * Checks if the component is the main Settings tree (inside SettingsTreeView).
     * The main Settings tree's path is already included via getPathNames(), so we skip
     * tree path extraction for it. Secondary trees within settings pages (like the
     * color scheme tree) are not inside SettingsTreeView and should be extracted.
     */
    private fun isMainSettingsTree(src: Component): Boolean {
        // Only check for JTree components
        val tree = generateSequence(src) { it.parent }
            .firstOrNull { it is JTree } as? JTree ?: return false

        // Check if this tree is inside SettingsTreeView (the main settings tree)
        return runCatching {
            val settingsTreeViewClass = Class.forName("com.intellij.openapi.options.newEditor.SettingsTreeView")
            ComponentUtil.getParentOfType(settingsTreeViewClass, tree) != null
        }.getOrDefault(false)
    }

    /**
     * Appends path information from a generic (non-Settings) dialog.
     */
    private fun appendGenericDialogPath(
        dialog: DialogWrapper,
        src: Component,
        path: StringBuilder,
        separator: String
    ) {
        appendItem(path, dialog.title, separator)

        if (dialog is SingleConfigurableEditor && ProjectStructurePathHandler.isSupported()) {
            ProjectStructurePathHandler.appendPath(dialog.configurable, path, separator)
        }

        // Add middle path (tabs, titled borders)
        SettingsPathExtractor.appendMiddlePath(src, null, path, separator)
    }

    /**
     * Appends tree/table/list path information if the source component is applicable.
     */
    private fun appendTreeOrTablePath(
        src: Component,
        e: AnActionEvent?,
        path: StringBuilder,
        separator: String
    ) {
        TreeTablePathExtractor.appendPath(src, e, path, separator)
    }
}
