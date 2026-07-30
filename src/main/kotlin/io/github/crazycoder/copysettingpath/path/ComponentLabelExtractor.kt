@file:Suppress("UNCHECKED_CAST")

package io.github.crazycoder.copysettingpath.path

import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.ui.dsl.gridLayout.Constraints
import com.intellij.ui.dsl.gridLayout.Grid
import com.intellij.ui.dsl.gridLayout.GridLayout
import io.github.crazycoder.copysettingpath.AdvancedSettingIds
import io.github.crazycoder.copysettingpath.LayoutConstants
import io.github.crazycoder.copysettingpath.appendItem
import io.github.crazycoder.copysettingpath.descendants
import io.github.crazycoder.copysettingpath.removeHtmlTags
import io.github.crazycoder.copysettingpath.selfAndDescendants
import io.github.crazycoder.copysettingpath.visibleText
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import javax.swing.*
import javax.swing.text.JTextComponent
import kotlin.math.min

/**
 * Simplified label extraction matching IntelliJ's CopySettingsPathAction pattern.
 *
 * Gets component labels using:
 * 1. labeledBy client property (Kotlin UI DSL standard)
 * 2. Component's own text (for buttons, labels)
 * 3. Adjacent value component for labels ending with ":"
 */
object ComponentLabelExtractor {

    /**
     * The grid column a row starts in. A cell further right shares its row with other cells,
     * so it is a value beside a label rather than a member of a vertical group.
     */
    private const val FIRST_COLUMN = 0

    /**
     * Appends the component label to the path.
     *
     * If the component text ends with ":" (colon), this indicates there is likely
     * an adjacent value component (combo box, text field, etc.). In such cases,
     * we find the adjacent component and append its current value.
     *
     * Special case: For toggle buttons (radio buttons, checkboxes) where the label
     * ends with ":" (either from labeledBy or from a sibling's group label),
     * the value is the button's own text, not an adjacent component.
     *
     * @param component The component to extract label from.
     * @param path StringBuilder to append label to.
     * @param separator The separator to use between path components.
     */
    fun appendComponentLabel(component: Component, path: StringBuilder, separator: String) {
        val label = getComponentLabel(component) ?: return

        // Append the label using appendItem to handle separator correctly
        appendItem(path, label, separator)

        // If label ends with ":", try to append adjacent value
        // Note: appendItem already adds a space after labels ending with ":"
        if (label.endsWith(":") && isAdjacentValueIncluded()) {
            // Special case: for toggle buttons where label came from labeledBy or group label,
            // the value is the button's own text, not an adjacent component
            if (component is JToggleButton && isToggleButtonGroupLabel(component, label)) {
                val buttonText = component.text?.removeHtmlTags()?.trim()
                if (!buttonText.isNullOrBlank()) {
                    // Remove trailing space added by appendItem and append value with separator
                    if (path.endsWith(" ")) {
                        path.setLength(path.length - 1)
                    }
                    path.append(" ")
                    path.append(buttonText)
                    path.append(separator)
                }
            } else {
                findAdjacentValue(component)?.let { value ->
                    if (value.isNotBlank()) {
                        // Remove trailing space added by appendItem and append value with separator
                        if (path.endsWith(" ")) {
                            path.setLength(path.length - 1)
                        }
                        path.append(" ")
                        path.append(value)
                        path.append(separator)
                    }
                }
            }
        }
    }

    /**
     * Checks if the label for a toggle button came from labeledBy or a sibling's group label.
     * This is used to determine if the button's own text should be used as the value.
     */
    private fun isToggleButtonGroupLabel(component: JToggleButton, label: String): Boolean =
        component.getClientProperty("labeledBy") is JLabel || findGroupLabel(component) == label

    /**
     * Gets the label for a component using standard patterns.
     *
     * Follows IntelliJ's CopySettingsPathAction pattern:
     * 1. Check labeledBy client property (standard Swing/Kotlin UI DSL pattern)
     * 2. For toggle buttons without labeledBy, check sibling buttons for a shared group label
     * 3. Fall back to component's own text
     *
     * @param component The component to get label for.
     * @return The label text, or null if not found.
     */
    fun getComponentLabel(component: Component): String? {
        // Check labeledBy first (standard Swing/Kotlin UI DSL pattern)
        if (component is JComponent) {
            val labeledBy = component.getClientProperty("labeledBy")
            if (labeledBy is JLabel) {
                val text = labeledBy.text?.removeHtmlTags()?.trim()
                if (!text.isNullOrEmpty()) {
                    return text
                }
            }

            // For toggle buttons without labeledBy, check if sibling buttons have a shared group label
            if (component is JToggleButton) {
                val groupLabel = findGroupLabel(component)
                if (groupLabel != null) {
                    return groupLabel
                }
            }
        }

        // Fall back to component's own text
        return when (component) {
            is JLabel -> component.text?.removeHtmlTags()?.trim()?.takeIf { it.isNotEmpty() }
            is AbstractButton -> component.text?.removeHtmlTags()?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    /**
     * For a toggle button without labeledBy, looks for a sibling toggle button
     * that has a labeledBy label ending with ":". This handles cases where
     * only the first button in a group has the labeledBy property set.
     *
     * Only returns a group label if the sibling with that label is on the same
     * visual row as the target button. This prevents labels for horizontal
     * checkbox groups from being applied to checkboxes on different rows.
     *
     * @param button The toggle button to find the group label for.
     * @return The group label text if found, or null.
     */
    private fun findGroupLabelFromSiblings(button: JToggleButton): String? {
        val parent = button.parent ?: return null
        val buttonBounds = getScreenBounds(button) ?: return null

        // Look for sibling toggle buttons with labeledBy that ends with ":"
        for (sibling in parent.components) {
            if (sibling === button) continue
            if (sibling !is JToggleButton) continue

            val labeledBy = sibling.getClientProperty("labeledBy")
            if (labeledBy is JLabel) {
                val labelText = labeledBy.text?.removeHtmlTags()?.trim()
                if (!labelText.isNullOrEmpty() && labelText.endsWith(":")) {
                    // Only use this group label if the sibling is on the same row
                    if (isOnSameRow(buttonBounds, sibling)) {
                        return labelText
                    }
                }
            }
        }

        return null
    }

    /**
     * Returns the label of the group a toggle button belongs to, or null when it is not in one.
     *
     * A group label is laid out in one of two ways. A horizontal group keeps the label on the row
     * of the buttons, where it belongs to the first button. A vertical group puts the label on a
     * row of its own above the buttons, where nothing connects the two.
     *
     * @param button The toggle button to find the group label for.
     * @return The group label text if found, or null.
     */
    private fun findGroupLabel(button: JToggleButton): String? =
        findGroupLabelFromSiblings(button) ?: findButtonsGroupHeader(button)

    /**
     * Returns the title of the Kotlin UI DSL buttons group the button belongs to.
     *
     * `buttonsGroup("Title:")` writes the title into a row of its own and indents the buttons
     * below it, and leaves no reference between the label and the buttons. Only the layout still
     * holds the relation: every row of a panel is a cell of one grid, and the indent of a row is
     * the left gap of that cell. The title is therefore the nearest row above the button that is
     * indented less than the button is.
     *
     * The search is deliberately narrow, because a settings page carries many labels that title
     * nothing. It gives up unless every row between the button and the candidate is indented
     * exactly like the button, the candidate row holds a single label and nothing else, and that
     * label ends with ":". A grid is never left, so a group in one column cannot take the title
     * of the column beside it, and a page built without the UI DSL has no grid and is left alone.
     *
     * @param button The toggle button to find the group title for.
     * @return The group title if the button sits in a titled buttons group, or null.
     */
    @Suppress("UnstableApiUsage") // The grid layout API carries the only link left between the two
    private fun findButtonsGroupHeader(button: JToggleButton): String? {
        val panel = button.parent as? JComponent ?: return null
        val layout = panel.layout as? GridLayout ?: return null
        val cell = findIndentedCell(layout, button) ?: return null
        if (cell.x != FIRST_COLUMN) return null

        val rows = collectRows(panel, layout, cell.grid)
        var y = cell.y - 1
        while (y >= 0) {
            val row = rows[y] ?: return null
            y--
            // A row hidden by visibleIf keeps its cell, but the user does not see it
            if (row.none { (_, component) -> component.isVisible }) continue
            val indent = row.minOf { (constraints, _) -> constraints.gaps.left }
            // Another row of the same group
            if (indent == cell.gaps.left) continue
            if (indent > cell.gaps.left) return null
            val label = row.singleOrNull()?.second as? JLabel ?: return null
            return label.text?.removeHtmlTags()?.trim()?.takeIf { it.endsWith(":") }
        }

        return null
    }

    /**
     * Returns the cell that carries the indent of the row the component sits in.
     *
     * A row is a sub grid of the panel grid, so the indent is one or two levels above the cell of
     * the component itself. Reaching the root grid without finding a left gap means the component
     * is not indented, and an unindented component cannot be below a group title.
     *
     * @param layout The grid layout of the panel the component belongs to.
     * @param component The component to find the indented cell for.
     * @return The cell that carries the indent, or null if there is none.
     */
    @Suppress("UnstableApiUsage")
    private fun findIndentedCell(layout: GridLayout, component: JComponent): Constraints? {
        var constraints = layout.getConstraints(component)
        while (constraints != null) {
            if (constraints.gaps.left > 0) {
                return constraints
            }
            constraints = layout.getConstraints(constraints.grid)
        }
        return null
    }

    /**
     * Groups the children of a panel by the row they occupy in the given grid.
     *
     * A child of a nested panel belongs to another grid and resolves to no cell in this one, so
     * it is left out.
     *
     * @param panel The panel that owns the layout.
     * @param layout The grid layout of the panel.
     * @param grid The grid whose rows are wanted.
     * @return The cells of the grid, and the components in them, by row.
     */
    @Suppress("UnstableApiUsage")
    private fun collectRows(
        panel: JComponent,
        layout: GridLayout,
        grid: Grid
    ): Map<Int, List<Pair<Constraints, Component>>> {
        val rows = mutableMapOf<Int, MutableList<Pair<Constraints, Component>>>()
        for (child in panel.components) {
            if (child !is JComponent) continue
            var constraints = layout.getConstraints(child)
            while (constraints != null && constraints.grid !== grid) {
                constraints = layout.getConstraints(constraints.grid)
            }
            if (constraints != null) {
                rows.getOrPut(constraints.y) { mutableListOf() }.add(constraints to child)
            }
        }
        return rows
    }

    /**
     * Finds the adjacent value component for a label ending with ":".
     *
     * @param src The source component (typically a JLabel ending with ":").
     * @return The extracted value string, or null if not found.
     */
    private fun findAdjacentValue(src: Component): String? {
        val adjacent = findAdjacentComponent(src) ?: return null
        // Values are appended to the path directly rather than through appendItem, so they are
        // cleaned here. A JTextField or a JCheckBox can hold markup of its own.
        return extractComponentValue(adjacent)?.removeHtmlTags()
    }

    /**
     * Returns whether adjacent value should be included for labels ending with colon.
     */
    private fun isAdjacentValueIncluded(): Boolean =
        AdvancedSettings.getBoolean(AdvancedSettingIds.INCLUDE_ADJACENT_VALUE)

    // ========================================================================
    // Adjacent Component Detection (simplified from AdjacentComponentUtils)
    // ========================================================================

    /**
     * Finds the adjacent component that follows the given source component.
     */
    private fun findAdjacentComponent(src: Component): Component? {
        val parent = src.parent ?: return null

        // Priority 1: If source is a JLabel with labelFor set, return that component
        if (src is JLabel) {
            val labelFor = src.labelFor
            if (labelFor != null && isValueComponent(labelFor)) {
                return labelFor
            }
        }

        val srcScreenBounds = getScreenBounds(src) ?: return null

        // Priority 2: Look for the next visible value component among siblings
        val components = parent.components
        val srcIndex = components.indexOf(src)
        if (srcIndex >= 0) {
            for (i in (srcIndex + 1) until components.size) {
                val nextComponent = components[i]
                if (!nextComponent.isVisible) continue

                if (isValueComponent(nextComponent) && isOnSameRow(srcScreenBounds, nextComponent)) {
                    return nextComponent
                }

                if (nextComponent is Container) {
                    val valueComp = findValueComponentIn(nextComponent)
                    if (valueComp != null && isOnSameRow(srcScreenBounds, valueComp)) {
                        return valueComp
                    }
                }
            }
        }

        return null
    }

    /**
     * Extracts the display value from a component.
     */
    private fun extractComponentValue(component: Component): String? {
        return when (component) {
            is JComboBox<*> -> extractComboBoxDisplayText(component)
            is JButton -> component.text?.takeIf { it.isNotBlank() }
            is JTextField -> component.text ?: ""
            is JCheckBox -> {
                if (component.text.isNullOrBlank()) {
                    if (component.isSelected) "Enabled" else "Disabled"
                } else {
                    component.text
                }
            }

            is JRadioButton -> component.text?.takeIf { component.isSelected }
            is JSpinner -> component.value?.toString()
            is JSlider -> component.value.toString()
            is JTextComponent -> component.text ?: ""
            else -> extractValueViaReflection(component)
        }
    }

    /**
     * Recursively searches a container for a value component.
     *
     * Hidden subtrees are skipped rather than searched, so a value component that the user
     * cannot see is never picked up.
     */
    private fun findValueComponentIn(container: Component): Component? {
        if (isValueComponent(container)) return container
        if (container !is Container) return null
        return descendants(container, descendInto = { it.isVisible })
            .filter { it.isVisible }
            .firstOrNull { isValueComponent(it) }
    }

    /**
     * Checks if a component is a value-bearing component.
     */
    private fun isValueComponent(component: Component): Boolean {
        // Exclude large components
        if (component.height > LayoutConstants.MAX_VALUE_COMPONENT_HEIGHT ||
            component.width > LayoutConstants.MAX_VALUE_COMPONENT_WIDTH
        ) {
            val className = component.javaClass.simpleName
            if (!className.contains("ComboBox", ignoreCase = true) &&
                !className.contains("TextField", ignoreCase = true)
            ) {
                return false
            }
        }

        if (component is JTextArea || component is JEditorPane) {
            return false
        }

        return when (component) {
            is JComboBox<*>, is JTextField, is JCheckBox,
            is JRadioButton, is JSpinner, is JSlider -> true

            is JButton -> component.javaClass.simpleName.contains("ComboBoxButton", ignoreCase = true)
            else -> {
                val className = component.javaClass.simpleName
                (className.contains("ComboBoxButton", ignoreCase = true) ||
                        className.contains("ComboBox", ignoreCase = true)) &&
                        !className.contains("Panel", ignoreCase = true)
            }
        }
    }

    /**
     * Gets the screen bounds of a component, or null if not available.
     */
    private fun getScreenBounds(component: Component): Rectangle? {
        return runCatching {
            val location = component.locationOnScreen
            Rectangle(location.x, location.y, component.width, component.height)
        }.getOrNull()
    }

    /**
     * Checks if a component is on the same visual row as the source.
     */
    private fun isOnSameRow(srcBounds: Rectangle, component: Component): Boolean {
        val compBounds = getScreenBounds(component) ?: return false

        val srcCenterY = srcBounds.y + srcBounds.height / 2
        val compCenterY = compBounds.y + compBounds.height / 2
        val maxCenterYDiff = min(srcBounds.height, compBounds.height) / 2 + LayoutConstants.ROW_ALIGNMENT_TOLERANCE

        return kotlin.math.abs(srcCenterY - compCenterY) <= maxCenterYDiff
    }

    /**
     * Extracts the display text from a JComboBox using its renderer.
     */
    private fun extractComboBoxDisplayText(comboBox: JComboBox<*>): String? {
        val selectedItem = comboBox.selectedItem ?: return null
        val selectedIndex = comboBox.selectedIndex

        runCatching {
            val renderer = comboBox.renderer as? ListCellRenderer<Any?>
            if (renderer != null) {
                val renderedComponent = renderer.getListCellRendererComponent(
                    JList<Any?>(),
                    selectedItem,
                    selectedIndex,
                    false,
                    false
                )

                val text = renderedComponent.selfAndDescendants().firstNotNullOfOrNull { it.visibleText() }
                if (!text.isNullOrBlank()) {
                    return text
                }
            }
        }

        return selectedItem.toString()
    }

    /**
     * Attempts to extract a value from a component using reflection.
     */
    private fun extractValueViaReflection(component: Component): String? {
        val methodsToTry = listOf("getSelectedItem", "getText", "getValue", "getSelectedValue")

        for (methodName in methodsToTry) {
            runCatching {
                val method = component.javaClass.getMethod(methodName)
                method.isAccessible = true
                val result = method.invoke(component)
                result?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }

        return null
    }
}
