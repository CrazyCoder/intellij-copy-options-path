package io.github.crazycoder.copysettingpath.path

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.options.ex.SingleConfigurableEditor
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ComponentUtil
import io.github.crazycoder.copysettingpath.PathConstants
import io.github.crazycoder.copysettingpath.appendItem
import io.github.crazycoder.copysettingpath.descendants
import io.github.crazycoder.copysettingpath.visibleText
import java.awt.Component
import java.awt.Container
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.JLabel

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
     * Checks whether a path could be built for the component.
     *
     * Both the action's update() and the mouse interceptor ask this, so the contexts the action
     * is enabled in and the contexts the interceptor swallows a click in cannot drift apart.
     *
     * @param src The source UI component.
     * @param e The action event, whose data context is preferred when available.
     * @return true if the component sits in a context this plugin can build a path for.
     */
    fun hasPathContext(src: Component?, e: AnActionEvent? = null): Boolean {
        if (src == null) return false
        if (DialogWrapper.findInstance(src) != null) return true
        if (PopupPathExtractor.isInPopupContext(src)) return true

        val dataContext = e?.dataContext ?: DataManager.getInstance().getDataContext(src)
        return SettingsPathExtractor.isInSettingsWindow(dataContext) ||
                PlatformDataKeys.TOOL_WINDOW.getData(dataContext) != null
    }

    /**
     * Builds the complete setting path for the given source component.
     *
     * @param src The source UI component.
     * @param e The action event containing context information.
     * @param separator The separator to use between path components.
     * @return The built path string, or null if path cannot be determined.
     */
    fun buildPath(src: Component, e: AnActionEvent?, separator: String): String? {
        // 1. Try Settings path (highest priority)
        // Checked before the dialog to cover both the modal Settings dialog (up to 2026.1)
        // and the non-modal Settings window (2026.2+), which is not a DialogWrapper.
        if (SettingsPathExtractor.isInSettingsWindow(src, e)) {
            return buildSettingsPath(src, e, separator)
        }

        // 2. Try dialog-based path
        val dialog = DialogWrapper.findInstance(src)
        if (dialog != null) {
            return buildDialogPath(src, e, dialog, separator)
        }

        // 3. Try popup path (JBPopup-based floating dialogs)
        val popupPath = PopupPathExtractor.buildPath(src, e, separator)
        if (popupPath != null) {
            return popupPath
        }

        // 4. Try tool window path
        val toolWindowPath = ToolWindowPathExtractor.buildPath(src, e, separator)
        if (toolWindowPath != null) {
            return toolWindowPath
        }

        // 5. No context found
        return null
    }

    /**
     * Builds the path for a component inside the Settings window.
     */
    private fun buildSettingsPath(src: Component, e: AnActionEvent?, separator: String): String? {
        val path = StringBuilder()
        // Resolve the editor once and hand it over, instead of letting the extractor look again
        val settingsEditor = SettingsPathExtractor.findSettingsEditor(src)
        SettingsPathExtractor.appendSettingsPath(src, path, separator, settingsEditor)
        return finishPath(src, e, path, separator)
    }

    /**
     * Builds the path for a component within a dialog.
     *
     * A single-configurable Settings dialog lands here too: it has no SettingsEditor, and its
     * title already carries the configurable name, so the generic handler covers it.
     */
    private fun buildDialogPath(
        src: Component,
        e: AnActionEvent?,
        dialog: DialogWrapper,
        separator: String
    ): String? {
        val path = StringBuilder()
        appendGenericDialogPath(dialog, src, path, separator)
        return finishPath(src, e, path, separator)
    }

    /**
     * Adds the parts that are common to every context: the tree, table or list path,
     * then the label of the source component itself.
     */
    private fun finishPath(
        src: Component,
        e: AnActionEvent?,
        path: StringBuilder,
        separator: String
    ): String? {
        // Add tree/table/list path if applicable
        // Skip only for the main Settings tree (inside SettingsTreeView) - getPathNames() already includes it
        // But include for secondary trees within settings pages (like color scheme tree)
        if (!isMainSettingsTree(src)) {
            appendTreeOrTablePath(src, e, path, separator)
        }

        // Add source component label
        ComponentLabelExtractor.appendComponentLabel(src, path, separator)

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
            val settingsTreeViewClass = Class.forName(PathConstants.SETTINGS_TREE_VIEW_CLASS)
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
        // Try dialog.title first, fall back to searching for title in dialog content
        val title = dialog.title?.takeIf { it.isNotBlank() }
            ?: extractTitleFromDialogContent(dialog, src)
        appendItem(path, title, separator)

        if (dialog is SingleConfigurableEditor && ProjectStructurePathHandler.isSupported()) {
            ProjectStructurePathHandler.appendPath(dialog.configurable, path, separator)
        }

        // Add middle path (tabs, titled borders)
        SettingsPathExtractor.appendMiddlePath(src, null, path, separator)
    }

    /**
     * Extracts title from dialog content when dialog.title is empty.
     *
     * Many dialogs (like Find in Files) use custom header panels with JLabel or
     * SimpleColoredComponent instead of setting the dialog title.
     */
    private fun extractTitleFromDialogContent(dialog: DialogWrapper, src: Component): String? {
        // Try from source component's window ancestor (more reliable path to content)
        val window = SwingUtilities.getWindowAncestor(src)
        if (window is Container) {
            val title = extractTitleFromContainer(window)
            if (title != null) return title
        }

        // Fallback to dialog's rootPane
        val rootPane = dialog.rootPane
        if (rootPane != null) {
            val title = extractTitleFromContainer(rootPane)
            if (title != null) return title
        }

        return null
    }

    /**
     * Extracts title from a container by searching for title-like components.
     */
    private fun extractTitleFromContainer(container: Container): String? {
        // First, look for labels in header components (like FindPopupHeader)
        val headerTitle = findLabelInHeaderComponent(container, maxDepth = 10)
        if (!headerTitle.isNullOrBlank()) {
            return headerTitle
        }

        // Then try to find bold labels
        val boldLabelTitle = TitleSearchUtils.findBoldLabelText(container, maxDepth = 10)
        if (!boldLabelTitle.isNullOrBlank()) {
            return boldLabelTitle
        }

        // Then try SimpleColoredComponent with bold text
        val boldColoredTitle = TitleSearchUtils.findBoldSimpleColoredText(container, maxDepth = 10)
        if (!boldColoredTitle.isNullOrBlank()) {
            return boldColoredTitle
        }

        // Fallback: find first title-like label (short, doesn't end with ":")
        val titleLikeLabel = TitleSearchUtils.findTitleLikeLabel(container, maxDepth = 10)
        if (!titleLikeLabel.isNullOrBlank()) {
            return titleLikeLabel
        }

        return null
    }

    /**
     * Finds a label inside a component that has "Header" in its class name.
     * This handles dialogs like Find in Files that use custom header panels.
     */
    private fun findLabelInHeaderComponent(container: Container, maxDepth: Int): String? =
        descendants(container, maxDepth)
            .filterIsInstance<Container>()
            .filter { it.javaClass.name.contains("Header", ignoreCase = true) }
            .firstNotNullOfOrNull { findFirstLabelInContainer(it) }

    /**
     * Finds the first non-empty label in a container (shallow search).
     */
    private fun findFirstLabelInContainer(container: Container): String? =
        descendants(container)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.visibleText() }
            .firstOrNull { !it.endsWith(":") }

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
