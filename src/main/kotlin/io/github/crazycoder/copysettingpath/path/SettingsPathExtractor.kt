package io.github.crazycoder.copysettingpath.path

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.options.ex.Settings
import com.intellij.ui.TitledSeparator
import com.intellij.ui.tabs.JBTabs
import io.github.crazycoder.copysettingpath.*
import java.awt.Component
import java.awt.Container
import java.util.*
import javax.swing.JComponent
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.border.TitledBorder

/**
 * Simplified Settings path extraction matching IntelliJ's CopySettingsPathAction pattern.
 *
 * The extraction follows this approach:
 * 1. Get base path from SettingsEditor.getPathNames()
 * 2. Walk up hierarchy for tabs and titled borders (within ConfigurableEditor boundary)
 * 3. Add TitledSeparator if present
 *
 * The Settings window is detected with the public [Settings] data key, and the path comes from
 * the SettingsEditor component itself rather than from the dialog. Up to 2026.1 the Settings
 * window is a modal SettingsDialog (a DialogWrapper); since 2026.2 it is a non-modal
 * SettingsNonModalDialog, which is not a DialogWrapper. Neither the data key nor the
 * SettingsEditor component depends on that difference.
 */
object SettingsPathExtractor {

    private const val SETTINGS_PREFIX = "Settings"

    /**
     * Checks whether the component belongs to the Settings window.
     *
     * Uses the public [Settings] data key, which the Settings UI publishes for its whole window.
     * That covers the settings pages and the window chrome alike, and does not rely on
     * [com.intellij.openapi.ui.DialogWrapper], which the non-modal Settings window has none of.
     *
     * @param component The component to check.
     * @param e The action event, whose data context is preferred when available.
     * @return true if the component is inside the Settings window.
     */
    fun isInSettingsWindow(component: Component, e: AnActionEvent? = null): Boolean =
        isInSettingsWindow(e?.dataContext ?: DataManager.getInstance().getDataContext(component))

    /**
     * Checks whether a data context belongs to the Settings window.
     *
     * @param dataContext The data context to check.
     * @return true if the context comes from the Settings window.
     */
    fun isInSettingsWindow(dataContext: DataContext): Boolean = Settings.KEY.getData(dataContext) != null

    /**
     * Finds the SettingsEditor that owns the given component.
     *
     * Normally the SettingsEditor is an ancestor of the component. Components that belong to the
     * window chrome rather than to the settings page (the button panel, for example) are outside
     * it, so the whole window is searched as a fallback. Callers reach this only after
     * [isInSettingsWindow] has confirmed the context, so the fallback never scans the main frame.
     *
     * @param component The component to start searching from.
     * @return The SettingsEditor component, or null if the component is not in a Settings window.
     */
    fun findSettingsEditor(component: Component): Component? {
        findParentOfType(component, PathConstants.SETTINGS_EDITOR_CLASS)?.let { return it }

        val window = SwingUtilities.getWindowAncestor(component) ?: return null
        return findAllComponentsOfType<Component>(window)
            .firstOrNull { isClassOrSubclassOf(it.javaClass, PathConstants.SETTINGS_EDITOR_CLASS) }
    }

    /**
     * Appends the Settings dialog path to the path builder.
     *
     * @param src The source component.
     * @param path StringBuilder to append path segments to.
     * @param separator The separator to use between path components.
     * @param settingsEditor The SettingsEditor resolved by the caller, if it already has one.
     */
    fun appendSettingsPath(
        src: Component,
        path: StringBuilder,
        separator: String,
        settingsEditor: Component? = findSettingsEditor(src)
    ) {
        // 1. Get base path from SettingsEditor.getPathNames()
        val settingsEditorPath = settingsEditor?.let { invokeGetPathNames(it) }
        if (!settingsEditorPath.isNullOrEmpty()) {
            path.append(SETTINGS_PREFIX)
            path.append(separator)
            path.append(settingsEditorPath.joinToString(separator))
            path.append(separator)
        } else if (settingsEditor != null) {
            // In the Settings window but no configurable selected yet
            appendItem(path, SETTINGS_PREFIX, separator)
        }

        // 2. Find ConfigurableEditor boundary
        val configurableEditor = findParentByClassName(src, PathConstants.CONFIGURABLE_EDITOR_CLASS)

        // 3. Collect middle path (tabs, titled borders) within ConfigurableEditor
        appendMiddlePath(src, configurableEditor, path, separator)

        // 4. Add TitledSeparator if present (but skip if src is itself a TitledSeparator or its child)
        if (!isInsideTitledSeparator(src)) {
            findPrecedingTitledSeparator(src, configurableEditor)?.let { separatorComponent ->
                appendItem(path, separatorComponent.text, separator)
            }
        }
    }

    /**
     * Appends middle path segments (tabs, titled borders) from the component hierarchy.
     *
     * This matches IntelliJ's CopySettingsPathAction approach:
     * - Walk up from component to boundary
     * - Collect JBTabs selected tab names
     * - Collect JTabbedPane selected tab titles
     * - Collect TitledBorder titles
     *
     * @param src The source component.
     * @param boundary The boundary component (ConfigurableEditor) to stop at.
     * @param path StringBuilder to append path segments to.
     * @param separator The separator to use between path components.
     */
    fun appendMiddlePath(src: Component, boundary: Component?, path: StringBuilder, separator: String) {
        val items = ArrayDeque<String>()
        var component: Component? = src

        while (component != null && component !== boundary) {
            collectTabName(component, items)
            collectTitledBorder(component, items)
            component = component.parent
        }

        // Add collected items in correct order (from root to leaf)
        for (item in items) {
            appendItem(path, item, separator)
        }
    }

    /**
     * Collects tab name from JBTabs, JTabbedPane, or ActionToolbar with toggle buttons.
     */
    private fun collectTabName(component: Component, items: ArrayDeque<String>) {
        when (component) {
            is JBTabs -> {
                component.selectedInfo?.text?.takeIf { it.isNotEmpty() }?.let {
                    items.addFirst(it)
                }
            }

            is JTabbedPane -> {
                val selectedIndex = component.selectedIndex
                if (selectedIndex >= 0 && selectedIndex < component.tabCount) {
                    component.getTitleAt(selectedIndex)?.takeIf { it.isNotEmpty() }?.let {
                        items.addFirst(it)
                    }
                }
            }

            is ActionToolbar -> {
                // Find selected toggle button in toolbar (e.g., scope selector in Find in Files)
                collectSelectedToggleButtonText(component, items)
            }
        }
    }

    /**
     * Finds the selected toggle button in an ActionToolbar and adds its text to items.
     * This handles toolbars like the scope selector in Find in Files (In Project/Module/Directory/Scope).
     */
    private fun collectSelectedToggleButtonText(toolbar: ActionToolbar, items: ArrayDeque<String>) {
        val toolbarComponent = toolbar.component
        for (child in toolbarComponent.components) {
            if (child is ActionButton) {
                val presentation = child.presentation
                if (Toggleable.isSelected(presentation)) {
                    val text = presentation.text?.removeHtmlTags()?.trim()
                    if (!text.isNullOrBlank()) {
                        items.addFirst(text)
                        return // Only add the first selected toggle
                    }
                }
            }
        }
    }

    /**
     * Collects titled border text from a JComponent.
     */
    private fun collectTitledBorder(component: Component, items: ArrayDeque<String>) {
        if (component is JComponent) {
            val border = component.border
            // TitledBorder includes IdeaTitledBorder (which extends TitledBorder)
            if (border is TitledBorder) {
                border.title?.takeIf { it.isNotEmpty() }?.let {
                    items.addFirst(it)
                }
            }
        }
    }

    /**
     * Finds the TitledSeparator that visually precedes the given component.
     *
     * Simplified version that assumes single-column layout (covers 95%+ of cases).
     *
     * @param component The component to find the preceding separator for.
     * @param boundary The boundary component to limit the search.
     * @return The TitledSeparator that precedes the component, or null if not found.
     */
    private fun findPrecedingTitledSeparator(component: Component, boundary: Component?): TitledSeparator? {
        val componentY = getAbsoluteY(component)
        val searchContainer = (boundary as? Container) ?: component.parent ?: return null

        var bestSeparator: TitledSeparator? = null
        var bestY = Int.MIN_VALUE

        findAllComponentsOfType<TitledSeparator>(searchContainer).forEach { separator ->
            if (!separator.isShowing) return@forEach
            val sepY = getAbsoluteY(separator)
            if (sepY in (bestY + 1)..<componentY) {
                bestSeparator = separator
                bestY = sepY
            }
        }

        return bestSeparator
    }

    /**
     * Checks if the component is a TitledSeparator or is contained within one.
     *
     * When clicking directly on a group title (TitledSeparator), we should not
     * search for a preceding TitledSeparator, as that would add an extra parent
     * group to the path.
     *
     * @param component The component to check.
     * @return true if the component is or is inside a TitledSeparator.
     */
    private fun isInsideTitledSeparator(component: Component): Boolean {
        var current: Component? = component
        while (current != null) {
            if (current is TitledSeparator) {
                return true
            }
            current = current.parent
        }
        return false
    }
}
