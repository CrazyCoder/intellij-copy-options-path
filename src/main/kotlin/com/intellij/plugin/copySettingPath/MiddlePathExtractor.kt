package com.intellij.plugin.copySettingPath

import com.intellij.ui.TitledSeparator
import com.intellij.ui.border.IdeaTitledBorder
import com.intellij.ui.tabs.JBTabs
import java.awt.Component
import java.awt.Container
import java.util.*
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JRadioButton
import javax.swing.JTabbedPane
import javax.swing.JToggleButton

/**
 * Utility functions for extracting middle path segments from the component hierarchy.
 *
 * Middle path segments include:
 * - Tab names from JBTabs and JTabbedPane
 * - Titled separator group names
 * - Titled border text
 * - Radio button group labels and hierarchy
 */

/**
 * Data class representing a toggle button with its screen position.
 * Used for grid detection and parent-child relationship analysis.
 */
private data class ToggleButtonPosition(val tb: JToggleButton, val y: Int, val x: Int)

/**
 * Extracts the middle path segments (tabs, titled borders, and titled separators) from the component hierarchy.
 *
 * This function collects UI elements that provide navigation context within a configurable panel:
 * - Tab names from JBTabs and JTabbedPane (but only within ConfigurableEditor boundary)
 * - Titled separator group names (e.g., "Java" section in Auto Import settings)
 * - Titled border text from panel borders
 *
 * Important: We only collect tabs that are within the ConfigurableEditor boundary to avoid
 * picking up tabs from the outer Settings dialog structure (which would add irrelevant paths
 * like "Project" from the main settings navigation).
 *
 * @param src The source component.
 * @param path StringBuilder to append path segments to.
 * @param separator The separator to use between path components.
 */
fun getMiddlePath(src: Component, path: StringBuilder, separator: String = PathConstants.SEPARATOR) {
    // Find the ConfigurableEditor boundary - we only collect tabs within this boundary
    val configurableEditor = findParentByClassName(src, PathConstants.CONFIGURABLE_EDITOR_CLASS)

    // Collect tabs and titled borders from src up to ConfigurableEditor (exclusive)
    val middlePathItems = ArrayDeque<String>()
    var component: Component? = src

    while (component != null && component !== configurableEditor) {
        collectTabName(component, middlePathItems)
        collectTitledBorder(component, middlePathItems)
        component = component.parent
    }

    // Add collected items in correct order
    for (item in middlePathItems) {
        appendItem(path, item, separator)
    }

    // Add titled separator group name if present
    findPrecedingTitledSeparator(src, configurableEditor)?.let { titledSeparator ->
        appendItem(path, titledSeparator.text, separator)
    }

    // Add toggle button hierarchy with group label in correct position
    if (src is JToggleButton) {
        // Find the parent toggle buttons hierarchy
        val parentToggleButtons = findParentToggleButtonComponents(src, configurableEditor)

        // Find the group label for the SOURCE component (not the topmost parent)
        val groupLabelInfo = findToggleButtonGroupLabelWithPosition(src, configurableEditor)

        if (groupLabelInfo != null) {
            val (groupLabel, groupLabelY) = groupLabelInfo

            // Split parents into those ABOVE vs BELOW the group label
            // Parents above the group label come first, then the group label, then parents below
            val parentsAboveLabel = mutableListOf<JToggleButton>()
            val parentsBelowLabel = mutableListOf<JToggleButton>()

            for (parent in parentToggleButtons) {
                val parentY = getAbsoluteY(parent)
                if (parentY < groupLabelY) {
                    parentsAboveLabel.add(parent)
                } else {
                    parentsBelowLabel.add(parent)
                }
            }

            // Add parents above the group label first
            parentsAboveLabel.forEach { parent ->
                val parentText = parent.text?.removeHtmlTags()?.trim()
                if (!parentText.isNullOrEmpty()) {
                    appendItem(path, parentText, separator)
                }
            }

            // Add the group label
            appendItem(path, groupLabel, separator)

            // Add parents below the group label (these are part of the same labeled group)
            parentsBelowLabel.forEach { parent ->
                val parentText = parent.text?.removeHtmlTags()?.trim()
                if (!parentText.isNullOrEmpty()) {
                    appendItem(path, parentText, separator)
                }
            }
        } else {
            // No group label - just add all parents
            parentToggleButtons.forEach { parent ->
                val parentText = parent.text?.removeHtmlTags()?.trim()
                if (!parentText.isNullOrEmpty()) {
                    appendItem(path, parentText, separator)
                }
            }
        }
    }
}

/**
 * Collects tab name from JBTabs or JTabbedPane component.
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
    }
}

/**
 * Collects titled border text from a JComponent.
 */
private fun collectTitledBorder(component: Component, items: ArrayDeque<String>) {
    if (component is JComponent) {
        val border = component.border
        if (border is IdeaTitledBorder) {
            border.title?.takeIf { it.isNotEmpty() }?.let {
                items.addFirst(it)
            }
        }
    }
}

/**
 * Finds the TitledSeparator that visually precedes the given component.
 *
 * TitledSeparators are used in Settings panels to group related options
 * (e.g., "Java" section in Auto Import settings). This function searches
 * only within the boundary component to find the separator that appears
 * before the target component in the visual layout.
 *
 * For side-by-side layouts (e.g., "Relative Location" on left, "Borders" on right),
 * this function considers horizontal position to avoid matching separators from
 * different columns.
 *
 * @param component The component to find the preceding separator for.
 * @param boundary The boundary component (typically ConfigurableEditor) to limit the search.
 * @return The TitledSeparator that precedes the component, or null if not found.
 */
private fun findPrecedingTitledSeparator(component: Component, boundary: Component?): TitledSeparator? {
    val componentY = getAbsoluteY(component)
    val componentX = getAbsoluteX(component)
    val searchContainer = (boundary as? Container) ?: component.parent ?: return null

    var bestSeparator: TitledSeparator? = null
    var bestSeparatorY = Int.MIN_VALUE

    // Collect all separators with their positions
    data class SeparatorInfo(val separator: TitledSeparator, val y: Int, val x: Int)
    val separators = mutableListOf<SeparatorInfo>()

    findAllComponentsOfType<TitledSeparator>(searchContainer).forEach { separator ->
        if (!separator.isShowing) return@forEach
        val separatorY = getAbsoluteY(separator)
        if (separatorY <= componentY) {
            separators.add(SeparatorInfo(separator, separatorY, getAbsoluteX(separator)))
        }
    }

    // Detect if this is a multi-column layout by checking if any separators are side-by-side
    val isMultiColumnLayout = separators.any { sep1 ->
        separators.any { sep2 ->
            sep1 !== sep2 &&
                    kotlin.math.abs(sep1.y - sep2.y) < LayoutConstants.SAME_ROW_THRESHOLD &&
                    kotlin.math.abs(sep1.x - sep2.x) > LayoutConstants.MAX_HORIZONTAL_DISTANCE
        }
    }

    // Find the closest separator that the component is horizontally aligned with
    for (sepInfo in separators) {
        if (sepInfo.y > bestSeparatorY) {
            if (isMultiColumnLayout) {
                // In multi-column layouts, only consider separators where the component
                // is horizontally close to or to the right of the separator
                // (components are typically indented under their separator)
                val horizontalDiff = componentX - sepInfo.x
                
                // Skip if component is too far to the left of the separator
                if (horizontalDiff < -LayoutConstants.MIN_INDENT_DIFF) continue
                
                // Skip if component is too far to the right (likely in another column)
                // Check if there's another separator that's closer horizontally
                val closerSeparatorExists = separators.any { other ->
                    other !== sepInfo &&
                            other.y <= componentY &&
                            kotlin.math.abs(componentX - other.x) < kotlin.math.abs(componentX - sepInfo.x)
                }
                if (closerSeparatorExists && horizontalDiff > LayoutConstants.MAX_HORIZONTAL_DISTANCE) continue
            }

            bestSeparator = sepInfo.separator
            bestSeparatorY = sepInfo.y
        }
    }

    return bestSeparator
}

/**
 * Finds the group label for a toggle button (radio button or checkbox).
 *
 * In Kotlin UI DSL, toggle button groups created with `buttonsGroup(title)` or
 * panel groups with a label have a JLabel positioned either:
 * 1. Above the buttons (vertical layout) - serves as the group title (e.g., "Show in Reader mode:")
 * 2. To the left on the same row (horizontal layout) - e.g., "Placement:" followed by Top/Bottom buttons
 *
 * This function ensures the toggle button is actually part of the labeled group by:
 * 1. Finding candidate labels above or on the same row (to the left) of the button
 * 2. Verifying there are no group boundaries (TitledSeparators or other group labels) between them
 * 3. Checking that the button is within a reasonable distance from the label
 *
 * @param toggleButton The toggle button (JRadioButton or JCheckBox) to find the group label for.
 * @param boundary The boundary component (typically ConfigurableEditor) to limit the search.
 * @return The group label text, or null if not found.
 */
private fun findToggleButtonGroupLabel(toggleButton: JToggleButton, boundary: Component?): String? {
    return findToggleButtonGroupLabelWithPosition(toggleButton, boundary)?.first
}

/**
 * Finds the group label for a toggle button and returns both the label text and its Y position.
 *
 * This variant is useful when we need to determine whether parent toggle buttons
 * are above or below the group label in the visual hierarchy.
 *
 * @param toggleButton The toggle button to find the group label for.
 * @param boundary The boundary component to limit the search.
 * @return A Pair of (label text, Y position), or null if not found.
 */
private fun findToggleButtonGroupLabelWithPosition(toggleButton: JToggleButton, boundary: Component?): Pair<String, Int>? {
    val buttonY = getAbsoluteY(toggleButton)
    val buttonX = getAbsoluteX(toggleButton)
    val searchContainer = (boundary as? Container) ?: toggleButton.parent ?: return null

    // First, check for same-row label (horizontal layout like "Placement: (Top) (Bottom)")
    val sameRowResult = findSameRowLabelWithPosition(toggleButton, buttonY, buttonX, searchContainer)
    if (sameRowResult != null) return sameRowResult

    // Fall back to above-label detection (vertical layout)
    return findAboveLabelWithPosition(toggleButton, buttonY, buttonX, searchContainer)
}

/**
 * Finds a label on the same row to the left of the toggle button.
 * This handles horizontal radio button layouts like "Placement: (Top) (Bottom)".
 */
private fun findSameRowLabel(
    toggleButton: JToggleButton,
    buttonY: Int,
    buttonX: Int,
    searchContainer: Container
): String? {
    return findSameRowLabelWithPosition(toggleButton, buttonY, buttonX, searchContainer)?.first
}

/**
 * Finds a label on the same row to the left of the toggle button, returning both text and Y position.
 */
private fun findSameRowLabelWithPosition(
    toggleButton: JToggleButton,
    buttonY: Int,
    buttonX: Int,
    searchContainer: Container
): Pair<String, Int>? {
    var bestLabel: JLabel? = null
    var bestLabelX = Int.MIN_VALUE
    var bestLabelY = 0

    findAllComponentsOfType<JLabel>(searchContainer).forEach { label ->
        if (!label.isShowing) return@forEach

        // Skip labels that have labelFor set to a component of different type
        val labelFor = label.labelFor
        if (labelFor != null && !isSameToggleButtonType(labelFor, toggleButton)) return@forEach

        val labelText = label.text?.removeHtmlTags()?.trim()
        if (labelText.isNullOrEmpty()) return@forEach

        // Only consider labels ending with ":" as group labels
        if (!labelText.endsWith(":")) return@forEach

        val labelY = getAbsoluteY(label)
        val labelX = getAbsoluteX(label)

        // Check if label is on the same row (within threshold)
        val verticalDistance = kotlin.math.abs(labelY - buttonY)
        if (verticalDistance > LayoutConstants.SAME_ROW_THRESHOLD) return@forEach

        // Label must be to the left of the button
        if (labelX >= buttonX) return@forEach

        // Must be within reasonable horizontal distance
        val horizontalDistance = buttonX - labelX
        if (horizontalDistance > LayoutConstants.MAX_HORIZONTAL_DISTANCE) return@forEach

        // Pick the closest label to the left (largest X that's still < buttonX)
        if (labelX > bestLabelX) {
            bestLabel = label
            bestLabelX = labelX
            bestLabelY = labelY
        }
    }

    val text = bestLabel?.text?.removeHtmlTags()?.trim() ?: return null
    return Pair(text, bestLabelY)
}

/**
 * Finds a label above the toggle button (vertical layout).
 * This is the original behavior for layouts where the label is above the buttons.
 */
private fun findAboveLabel(
    toggleButton: JToggleButton,
    buttonY: Int,
    buttonX: Int,
    searchContainer: Container
): String? {
    return findAboveLabelWithPosition(toggleButton, buttonY, buttonX, searchContainer)?.first
}

/**
 * Finds a label above the toggle button, returning both text and Y position.
 */
private fun findAboveLabelWithPosition(
    toggleButton: JToggleButton,
    buttonY: Int,
    buttonX: Int,
    searchContainer: Container
): Pair<String, Int>? {
    var bestLabel: JLabel? = null
    var bestLabelY = Int.MIN_VALUE

    // Collect all group-label-like labels (ending with ":")
    val groupLabels = mutableListOf<Pair<JLabel, Int>>()

    findAllComponentsOfType<JLabel>(searchContainer).forEach { label ->
        if (!label.isShowing) return@forEach

        // Skip labels that have labelFor set to a component of different type
        val labelFor = label.labelFor
        if (labelFor != null && !isSameToggleButtonType(labelFor, toggleButton)) return@forEach

        val labelText = label.text?.removeHtmlTags()?.trim()
        if (labelText.isNullOrEmpty()) return@forEach

        val labelY = getAbsoluteY(label)
        val labelX = getAbsoluteX(label)

        // The group label must be above the toggle button (not on the same row - that's handled separately)
        if (labelY >= buttonY - LayoutConstants.SAME_ROW_THRESHOLD) return@forEach

        // The group label should be roughly aligned horizontally
        val horizontalDistance = kotlin.math.abs(labelX - buttonX)
        if (horizontalDistance > LayoutConstants.MAX_HORIZONTAL_DISTANCE) return@forEach

        // Track labels that look like group labels (ending with ":")
        if (labelText.endsWith(":")) {
            groupLabels.add(Pair(label, labelY))
        }

        if (labelY > bestLabelY) {
            bestLabel = label
            bestLabelY = labelY
        }
    }

    // If no label found, return null
    val foundLabel = bestLabel ?: return null

    val bestLabelText = foundLabel.text?.removeHtmlTags()?.trim() ?: return null

    // Only consider labels that end with ":" as group labels
    if (!bestLabelText.endsWith(":")) return null

    val labelX = getAbsoluteX(foundLabel)

    // In Kotlin UI DSL, checkboxes within a group are indented relative to the group label.
    // If the button is at the same X position or less indented than the label, it's outside the group.
    if (buttonX <= labelX + LayoutConstants.MIN_INDENT_DIFF) {
        return null
    }

    // Check if there's a TitledSeparator between the label and the button
    // If so, the button is not part of this group
    if (hasInterveningSeparator(searchContainer, bestLabelY, buttonY)) {
        return null
    }

    // Check if there's another group label between the best label and the button
    // (a closer group label would indicate our button is outside the first group)
    for ((otherLabel, otherLabelY) in groupLabels) {
        if (otherLabel !== foundLabel && otherLabelY > bestLabelY && otherLabelY < buttonY) {
            // There's another group label between our candidate and the button
            // This means the button is not part of the candidate's group
            return null
        }
    }

    // Check if there's a parent checkbox between the label and the button.
    // If a checkbox exists that is above the button and less indented (acting as a parent),
    // and that checkbox is below the group label, then the button belongs to that checkbox, not the label.
    if (hasInterveningParentCheckbox(searchContainer, bestLabelY, buttonY, buttonX)) {
        return null
    }

    return Pair(bestLabelText, bestLabelY)
}

/**
 * Checks if there's a TitledSeparator between two Y coordinates.
 * This indicates a visual group boundary that separates UI elements.
 */
private fun hasInterveningSeparator(container: Container, topY: Int, bottomY: Int): Boolean {
    findAllComponentsOfType<TitledSeparator>(container).forEach { separator ->
        if (!separator.isShowing) return@forEach
        val separatorY = getAbsoluteY(separator)
        if (separatorY in (topY + 1) until bottomY) {
            return true
        }
    }
    return false
}

/**
 * Checks if there's a checkbox between the label and the button that acts as a parent.
 *
 * In some Settings panels, checkboxes can have child toggle buttons (radio buttons or other checkboxes)
 * indented below them. For example:
 * - "Format code according to preferred style" (checkbox)
 *   - "Use active scheme: Default" (radio button, indented child)
 *
 * If such a parent checkbox exists between the group label and the button, the button
 * belongs to that checkbox, not to the group label.
 *
 * @param container The container to search in.
 * @param labelY The Y coordinate of the group label.
 * @param buttonY The Y coordinate of the toggle button.
 * @param buttonX The X coordinate of the toggle button.
 * @return true if there's a parent checkbox between the label and the button.
 */
private fun hasInterveningParentCheckbox(container: Container, labelY: Int, buttonY: Int, buttonX: Int): Boolean {
    findAllComponentsOfType<JCheckBox>(container).forEach { checkbox ->
        if (!checkbox.isShowing) return@forEach

        val checkboxY = getAbsoluteY(checkbox)
        val checkboxX = getAbsoluteX(checkbox)

        // The checkbox must be between the label and the button (vertically)
        if (checkboxY !in (labelY + 1) until buttonY) return@forEach

        // The checkbox must be less indented than the button (acting as a parent)
        if (checkboxX < buttonX - LayoutConstants.MIN_INDENT_DIFF) {
            return true
        }
    }
    return false
}

/**
 * Checks if a labelFor component is the same type as the toggle button.
 * This ensures we don't match checkbox labels for radio buttons and vice versa.
 */
private fun isSameToggleButtonType(labelFor: Component, toggleButton: JToggleButton): Boolean {
    return when (toggleButton) {
        is JRadioButton -> labelFor is JRadioButton
        is JCheckBox -> labelFor is JCheckBox
        else -> labelFor is JToggleButton
    }
}

/**
 * Finds all parent toggle buttons (both checkboxes and radio buttons) in a hierarchical structure.
 *
 * In Settings panels, toggle buttons can be nested in various combinations:
 * - Checkbox → Radio button → Checkbox (mixed nesting)
 * - Checkbox → Checkbox (checkbox nesting)
 * - Radio button → Radio button (radio button nesting)
 *
 * This function finds ALL parent toggle buttons regardless of their type and returns them
 * in the correct hierarchical order based on their Y position and indentation.
 *
 * Key filtering rules:
 * - TitledSeparators act as boundaries - candidates separated by a TitledSeparator are excluded
 * - Group labels (ending with ":") block candidates unless there's an intermediate parent
 * - For radio buttons at the same level, only the selected one is considered a parent
 * - Grid layout siblings are excluded (checkboxes in same grid are siblings, not parents)
 *
 * @param toggleButton The toggle button to find parents for.
 * @param boundary The boundary component (typically ConfigurableEditor) to limit the search.
 * @return List of parent toggle button texts, ordered from top-most to closest parent.
 */
private fun findParentToggleButtons(toggleButton: JToggleButton, boundary: Component?): List<String> {
    val buttonY = getAbsoluteY(toggleButton)
    val buttonX = getAbsoluteX(toggleButton)
    val searchContainer = (boundary as? Container) ?: toggleButton.parent ?: return emptyList()

    // Collect all TitledSeparators with their positions for boundary checking
    data class SeparatorPos(val y: Int, val x: Int)
    val titledSeparators = mutableListOf<SeparatorPos>()
    findAllComponentsOfType<TitledSeparator>(searchContainer).forEach { separator ->
        if (!separator.isShowing) return@forEach
        val sepY = getAbsoluteY(separator)
        if (sepY < buttonY) {
            titledSeparators.add(SeparatorPos(sepY, getAbsoluteX(separator)))
        }
    }

    // Detect if this is a multi-column layout
    val isMultiColumnLayout = titledSeparators.any { sep1 ->
        titledSeparators.any { sep2 ->
            sep1 !== sep2 &&
                    kotlin.math.abs(sep1.y - sep2.y) < LayoutConstants.SAME_ROW_THRESHOLD &&
                    kotlin.math.abs(sep1.x - sep2.x) > LayoutConstants.MAX_HORIZONTAL_DISTANCE
        }
    }

    // Find the closest TitledSeparator above the target button, considering horizontal position
    // for side-by-side layouts (e.g., "Relative Location" on left, "Borders" on right)
    val closestSeparatorY = run {
        var bestY = Int.MIN_VALUE
        for (sep in titledSeparators) {
            if (sep.y <= bestY) continue

            if (isMultiColumnLayout) {
                val horizontalDiff = buttonX - sep.x
                
                // Skip if button is too far to the left of the separator
                if (horizontalDiff < -LayoutConstants.MIN_INDENT_DIFF) continue
                
                // Skip if there's a closer separator horizontally
                val closerSeparatorExists = titledSeparators.any { other ->
                    other !== sep &&
                            other.y < buttonY &&
                            kotlin.math.abs(buttonX - other.x) < kotlin.math.abs(buttonX - sep.x)
                }
                if (closerSeparatorExists && horizontalDiff > LayoutConstants.MAX_HORIZONTAL_DISTANCE) continue
            }

            bestY = sep.y
        }
        bestY
    }

    // Collect all group labels (ending with ":") with both Y and X positions
    // We need X to determine if the target is actually indented under the label
    data class GroupLabel(val y: Int, val x: Int)
    val groupLabels = mutableListOf<GroupLabel>()
    findAllComponentsOfType<JLabel>(searchContainer).forEach { label ->
        if (!label.isShowing) return@forEach
        val labelText = label.text?.removeHtmlTags()?.trim()
        if (!labelText.isNullOrEmpty() && labelText.endsWith(":")) {
            val labelY = getAbsoluteY(label)
            if (labelY < buttonY) {
                groupLabels.add(GroupLabel(labelY, getAbsoluteX(label)))
            }
        }
    }

    // Collect all toggle buttons with their positions to detect grid layouts
    val allToggleButtons = mutableListOf<ToggleButtonPosition>()
    findAllComponentsOfType<JToggleButton>(searchContainer).forEach { tb ->
        if (!tb.isShowing) return@forEach
        if (tb !is JCheckBox && tb !is JRadioButton) return@forEach
        allToggleButtons.add(ToggleButtonPosition(tb, getAbsoluteY(tb), getAbsoluteX(tb)))
    }

    // Detect grid siblings - checkboxes that are part of the same grid as the target
    // A grid is detected when multiple toggle buttons are on the same row (similar Y) with different X
    val gridSiblings = findGridSiblings(toggleButton, buttonY, buttonX, allToggleButtons)

    // Find the topmost checkbox in the panel - it may act as a "master" checkbox
    // that controls all options below it, even those at the same indentation level.
    // But only if there are items actually indented under it (proving it's a master, not just first).
    var topmostCheckbox: JCheckBox? = null
    var topmostCheckboxY = Int.MAX_VALUE
    var topmostCheckboxX = 0
    findAllComponentsOfType<JCheckBox>(searchContainer).forEach { cb ->
        if (cb === toggleButton || !cb.isShowing) return@forEach
        val cbY = getAbsoluteY(cb)
        if (cbY < topmostCheckboxY) {
            topmostCheckboxY = cbY
            topmostCheckboxX = getAbsoluteX(cb)
            topmostCheckbox = cb
        }
    }

    // Check if the topmost checkbox is actually a "master" checkbox.
    // A master checkbox has indented items DIRECTLY under it (before any TitledSeparator).
    // For example, in Reader Mode:
    //   - "Enable Reader mode" (X=20) <- topmost
    //   - "Show in Reader mode:" label
    //   - Indented items (X=60)       <- directly under topmost, proves it's a master
    // In Commit settings:
    //   - "Clear initial commit message" (X=30) <- topmost
    //   - "Commit Checks" TitledSeparator       <- separator breaks the relationship
    //   - Indented items (X=60)                 <- under the separator, not the checkbox
    val isTopmostActuallyMaster = topmostCheckbox != null && run {
        // Find the first TitledSeparator after the topmost checkbox
        var firstSeparatorY = Int.MAX_VALUE
        titledSeparators.forEach { sep ->
            if (sep.y in (topmostCheckboxY + 1) until firstSeparatorY) {
                firstSeparatorY = sep.y
            }
        }

        // Check if there are indented items between topmost checkbox and first separator
        // (or just after topmost if no separator)
        findAllComponentsOfType<JToggleButton>(searchContainer).any { tb ->
            if (tb === topmostCheckbox || !tb.isShowing) return@any false
            if (tb !is JCheckBox && tb !is JRadioButton) return@any false
            val tbX = getAbsoluteX(tb)
            val tbY = getAbsoluteY(tb)
            // Must be indented, after topmost, and BEFORE the first separator
            tbX > topmostCheckboxX + LayoutConstants.MIN_INDENT_DIFF &&
                    tbY > topmostCheckboxY && tbY < firstSeparatorY
        }
    }

    // First pass: collect all potential parent candidates based on position and indentation
    data class Candidate(val tb: JToggleButton, val y: Int, val x: Int)
    val allCandidates = mutableListOf<Candidate>()

    findAllComponentsOfType<JToggleButton>(searchContainer).forEach { tb ->
        // Skip if it's the same component or not a checkbox/radio button
        if (tb === toggleButton || !tb.isShowing) return@forEach
        if (tb !is JCheckBox && tb !is JRadioButton) return@forEach

        val tbY = getAbsoluteY(tb)
        val tbX = getAbsoluteX(tb)

        // Parent must be above (smaller Y)
        if (tbY >= buttonY) return@forEach

        // Skip candidates that are separated by a TitledSeparator
        // (they're in a different section and shouldn't be parents)
        if (tbY < closestSeparatorY) return@forEach

        // Skip grid siblings - they are peers in the same grid, not parents
        if (gridSiblings.contains(tb)) return@forEach

        // In multi-column layouts, skip candidates that are in a different column
        // (horizontally too far from the target button)
        if (isMultiColumnLayout) {
            val horizontalDistance = kotlin.math.abs(tbX - buttonX)
            if (horizontalDistance > LayoutConstants.MAX_HORIZONTAL_DISTANCE) return@forEach
        }

        // Check indentation requirement:
        // - Normal case: parent must be less indented (smaller X)
        // - Special case: topmost "master" checkbox can be a parent even at same indentation
        val isLessIndented = tbX < buttonX - LayoutConstants.MIN_INDENT_DIFF
        val isTopmostAtSameLevel = isTopmostActuallyMaster && tb === topmostCheckbox &&
                kotlin.math.abs(tbX - buttonX) <= LayoutConstants.MIN_INDENT_DIFF

        if (isLessIndented || isTopmostAtSameLevel) {
            allCandidates.add(Candidate(tb, tbY, tbX))
        }
    }

    // Second pass: filter candidates based on group label blocking and radio button selection
    // A group label only blocks a candidate if:
    // 1. It's between the candidate and the target (vertically)
    // 2. AND the target is indented relative to the label (horizontally)
    // 3. AND there's no intermediate candidate at the same level as the label
    // Exception: the topmost checkbox is never blocked - it's the "master" checkbox for the panel
    val parentCandidates = mutableListOf<Pair<JToggleButton, Int>>()

    // Group candidates by their indentation level for radio button selection filtering
    // Radio buttons at the same level form a group - only the selected one should be a parent
    val candidatesByLevel = allCandidates.groupBy { it.x }

    for (candidate in allCandidates) {
        // Topmost "master" checkbox is always a valid parent - it controls the entire panel
        if (isTopmostActuallyMaster && candidate.tb === topmostCheckbox) {
            parentCandidates.add(Pair(candidate.tb, candidate.y))
            continue
        }

        // For radio buttons at the same level, only the selected one should be a parent
        if (candidate.tb is JRadioButton) {
            val sameLevelCandidates = candidatesByLevel[candidate.x] ?: emptyList()
            val hasOtherRadioButtonsAtSameLevel = sameLevelCandidates.any {
                it !== candidate && it.tb is JRadioButton
            }
            if (hasOtherRadioButtonsAtSameLevel) {
                // This is a radio button group - only include if selected
                if (!candidate.tb.isSelected) continue
            }
        }

        val hasBlockingGroupLabel = groupLabels.any { (labelY, labelX) ->
            // Label must be between candidate and target
            if (labelY !in (candidate.y + 1) until buttonY) return@any false
            // Target must be indented under the label
            if (buttonX <= labelX + LayoutConstants.MIN_INDENT_DIFF) return@any false
            // Check if there's another candidate between the label and target at the same level as the label
            // If so, the target belongs to that candidate, not the label's group
            val hasIntermediateParentAtLabelLevel = allCandidates.any { other ->
                other !== candidate &&
                        other.y in (labelY + 1) until buttonY &&
                        kotlin.math.abs(other.x - labelX) <= LayoutConstants.MIN_INDENT_DIFF
            }
            !hasIntermediateParentAtLabelLevel
        }
        if (!hasBlockingGroupLabel) {
            parentCandidates.add(Pair(candidate.tb, candidate.y))
        }
    }

    return buildHierarchyFromCandidates(parentCandidates, buttonX)
}

/**
 * Finds all parent toggle buttons (both checkboxes and radio buttons) as components.
 *
 * This is a variant of [findParentToggleButtons] that returns the actual components
 * instead of their text labels. This is useful when we need to perform further analysis
 * on the parent components themselves (e.g., finding the group label for the topmost parent).
 *
 * @param toggleButton The toggle button to find parents for.
 * @param boundary The boundary component (typically ConfigurableEditor) to limit the search.
 * @return List of parent toggle button components, ordered from top-most to closest parent.
 */
private fun findParentToggleButtonComponents(toggleButton: JToggleButton, boundary: Component?): List<JToggleButton> {
    val buttonY = getAbsoluteY(toggleButton)
    val buttonX = getAbsoluteX(toggleButton)
    val searchContainer = (boundary as? Container) ?: toggleButton.parent ?: return emptyList()

    // Collect all TitledSeparators with their positions for boundary checking
    data class SeparatorPos(val y: Int, val x: Int)
    val titledSeparators = mutableListOf<SeparatorPos>()
    findAllComponentsOfType<TitledSeparator>(searchContainer).forEach { separator ->
        if (!separator.isShowing) return@forEach
        val sepY = getAbsoluteY(separator)
        if (sepY < buttonY) {
            titledSeparators.add(SeparatorPos(sepY, getAbsoluteX(separator)))
        }
    }

    // Detect if this is a multi-column layout
    val isMultiColumnLayout = titledSeparators.any { sep1 ->
        titledSeparators.any { sep2 ->
            sep1 !== sep2 &&
                    kotlin.math.abs(sep1.y - sep2.y) < LayoutConstants.SAME_ROW_THRESHOLD &&
                    kotlin.math.abs(sep1.x - sep2.x) > LayoutConstants.MAX_HORIZONTAL_DISTANCE
        }
    }

    // Find the closest TitledSeparator above the target button
    val closestSeparatorY = run {
        var bestY = Int.MIN_VALUE
        for (sep in titledSeparators) {
            if (sep.y <= bestY) continue

            if (isMultiColumnLayout) {
                val horizontalDiff = buttonX - sep.x
                if (horizontalDiff < -LayoutConstants.MIN_INDENT_DIFF) continue
                val closerSeparatorExists = titledSeparators.any { other ->
                    other !== sep &&
                            other.y < buttonY &&
                            kotlin.math.abs(buttonX - other.x) < kotlin.math.abs(buttonX - sep.x)
                }
                if (closerSeparatorExists && horizontalDiff > LayoutConstants.MAX_HORIZONTAL_DISTANCE) continue
            }

            bestY = sep.y
        }
        bestY
    }

    // Collect all group labels (ending with ":")
    data class GroupLabel(val y: Int, val x: Int)
    val groupLabels = mutableListOf<GroupLabel>()
    findAllComponentsOfType<JLabel>(searchContainer).forEach { label ->
        if (!label.isShowing) return@forEach
        val labelText = label.text?.removeHtmlTags()?.trim()
        if (!labelText.isNullOrEmpty() && labelText.endsWith(":")) {
            val labelY = getAbsoluteY(label)
            if (labelY < buttonY) {
                groupLabels.add(GroupLabel(labelY, getAbsoluteX(label)))
            }
        }
    }

    // Collect all toggle buttons with their positions
    val allToggleButtons = mutableListOf<ToggleButtonPosition>()
    findAllComponentsOfType<JToggleButton>(searchContainer).forEach { tb ->
        if (!tb.isShowing) return@forEach
        if (tb !is JCheckBox && tb !is JRadioButton) return@forEach
        allToggleButtons.add(ToggleButtonPosition(tb, getAbsoluteY(tb), getAbsoluteX(tb)))
    }

    val gridSiblings = findGridSiblings(toggleButton, buttonY, buttonX, allToggleButtons)

    // Find the topmost checkbox
    var topmostCheckbox: JCheckBox? = null
    var topmostCheckboxY = Int.MAX_VALUE
    var topmostCheckboxX = 0
    findAllComponentsOfType<JCheckBox>(searchContainer).forEach { cb ->
        if (cb === toggleButton || !cb.isShowing) return@forEach
        val cbY = getAbsoluteY(cb)
        if (cbY < topmostCheckboxY) {
            topmostCheckboxY = cbY
            topmostCheckboxX = getAbsoluteX(cb)
            topmostCheckbox = cb
        }
    }

    val isTopmostActuallyMaster = topmostCheckbox != null && run {
        var firstSeparatorY = Int.MAX_VALUE
        titledSeparators.forEach { sep ->
            if (sep.y in (topmostCheckboxY + 1) until firstSeparatorY) {
                firstSeparatorY = sep.y
            }
        }
        findAllComponentsOfType<JToggleButton>(searchContainer).any { tb ->
            if (tb === topmostCheckbox || !tb.isShowing) return@any false
            if (tb !is JCheckBox && tb !is JRadioButton) return@any false
            val tbX = getAbsoluteX(tb)
            val tbY = getAbsoluteY(tb)
            tbX > topmostCheckboxX + LayoutConstants.MIN_INDENT_DIFF &&
                    tbY > topmostCheckboxY && tbY < firstSeparatorY
        }
    }

    // First pass: collect all potential parent candidates
    data class Candidate(val tb: JToggleButton, val y: Int, val x: Int)
    val allCandidates = mutableListOf<Candidate>()

    findAllComponentsOfType<JToggleButton>(searchContainer).forEach { tb ->
        if (tb === toggleButton || !tb.isShowing) return@forEach
        if (tb !is JCheckBox && tb !is JRadioButton) return@forEach

        val tbY = getAbsoluteY(tb)
        val tbX = getAbsoluteX(tb)

        if (tbY >= buttonY) return@forEach
        if (tbY < closestSeparatorY) return@forEach
        if (gridSiblings.contains(tb)) return@forEach

        if (isMultiColumnLayout) {
            val horizontalDistance = kotlin.math.abs(tbX - buttonX)
            if (horizontalDistance > LayoutConstants.MAX_HORIZONTAL_DISTANCE) return@forEach
        }

        val isLessIndented = tbX < buttonX - LayoutConstants.MIN_INDENT_DIFF
        val isTopmostAtSameLevel = isTopmostActuallyMaster && tb === topmostCheckbox &&
                kotlin.math.abs(tbX - buttonX) <= LayoutConstants.MIN_INDENT_DIFF

        if (isLessIndented || isTopmostAtSameLevel) {
            allCandidates.add(Candidate(tb, tbY, tbX))
        }
    }

    // Second pass: filter candidates
    val parentCandidates = mutableListOf<Pair<JToggleButton, Int>>()
    val candidatesByLevel = allCandidates.groupBy { it.x }

    for (candidate in allCandidates) {
        if (isTopmostActuallyMaster && candidate.tb === topmostCheckbox) {
            parentCandidates.add(Pair(candidate.tb, candidate.y))
            continue
        }

        if (candidate.tb is JRadioButton) {
            val sameLevelCandidates = candidatesByLevel[candidate.x] ?: emptyList()
            val hasOtherRadioButtonsAtSameLevel = sameLevelCandidates.any {
                it !== candidate && it.tb is JRadioButton
            }
            if (hasOtherRadioButtonsAtSameLevel) {
                if (!candidate.tb.isSelected) continue
            }
        }

        val hasBlockingGroupLabel = groupLabels.any { (labelY, labelX) ->
            if (labelY !in (candidate.y + 1) until buttonY) return@any false
            if (buttonX <= labelX + LayoutConstants.MIN_INDENT_DIFF) return@any false
            val hasIntermediateParentAtLabelLevel = allCandidates.any { other ->
                other !== candidate &&
                        other.y in (labelY + 1) until buttonY &&
                        kotlin.math.abs(other.x - labelX) <= LayoutConstants.MIN_INDENT_DIFF
            }
            !hasIntermediateParentAtLabelLevel
        }
        if (!hasBlockingGroupLabel) {
            parentCandidates.add(Pair(candidate.tb, candidate.y))
        }
    }

    return buildComponentHierarchyFromCandidates(parentCandidates, buttonX)
}

/**
 * Detects grid siblings - toggle buttons that are part of the same grid as the target.
 *
 * A grid is a layout where multiple checkboxes are arranged in rows and columns:
 * ```
 * Languages:
 *   Groovy    Jupyter    XML
 *   HTML      Kotlin     YAML
 *   Java      Markdown
 * ```
 *
 * In a grid, items are siblings (not parent-child), even if they have different X positions.
 * This function identifies all toggle buttons that belong to the same grid as the target.
 *
 * Grid detection works by:
 * 1. Finding toggle buttons on the same row as the target (within SAME_ROW_THRESHOLD)
 * 2. If multiple items exist on the same row with different X positions, it's a grid
 * 3. Expanding to include all rows that share the same column structure
 *
 * @param target The target toggle button.
 * @param targetY The Y coordinate of the target.
 * @param targetX The X coordinate of the target.
 * @param allToggleButtons All toggle buttons with their positions.
 * @return Set of toggle buttons that are grid siblings (excluding the target itself).
 */
private fun findGridSiblings(
    target: JToggleButton,
    targetY: Int,
    targetX: Int,
    allToggleButtons: List<ToggleButtonPosition>
): Set<JToggleButton> {
    // Find items on the same row as the target
    val sameRowItems = allToggleButtons.filter { 
        it.tb !== target && 
        kotlin.math.abs(it.y - targetY) <= LayoutConstants.SAME_ROW_THRESHOLD 
    }

    // Check if there are items at different X positions on the same row - indicates a grid
    val hasMultipleColumns = sameRowItems.any { 
        kotlin.math.abs(it.x - targetX) > LayoutConstants.MIN_INDENT_DIFF 
    }

    if (!hasMultipleColumns) {
        // Not a grid layout - no siblings
        return emptySet()
    }

    // This is a grid. Collect all X positions (columns) in the target's row
    val gridColumns = mutableSetOf(targetX)
    sameRowItems.forEach { gridColumns.add(it.x) }

    // Find all rows that have items at the same column positions (part of the same grid)
    // Group all toggle buttons by their approximate row (Y coordinate)
    val rowGroups = allToggleButtons.groupBy { it.y / LayoutConstants.SAME_ROW_THRESHOLD }
    
    val gridSiblings = mutableSetOf<JToggleButton>()
    
    for ((_, rowItems) in rowGroups) {
        // Check if this row has items at similar column positions as our grid
        val rowColumnPositions = rowItems.map { it.x }.toSet()
        
        // A row is part of the grid if it has items at similar positions to our grid columns
        val isGridRow = gridColumns.any { gridCol ->
            rowColumnPositions.any { rowCol ->
                kotlin.math.abs(gridCol - rowCol) <= LayoutConstants.MIN_INDENT_DIFF
            }
        }
        
        if (isGridRow && rowItems.size > 1) {
            // Multiple items in the row at different columns - this is a grid row
            val hasMultipleColumnsInRow = rowItems.any { item1 ->
                rowItems.any { item2 ->
                    item1 !== item2 && kotlin.math.abs(item1.x - item2.x) > LayoutConstants.MIN_INDENT_DIFF
                }
            }
            if (hasMultipleColumnsInRow) {
                rowItems.forEach { 
                    if (it.tb !== target) {
                        gridSiblings.add(it.tb) 
                    }
                }
            }
        }
    }

    return gridSiblings
}

/**
 * Builds a hierarchy of parent component texts from sorted candidates.
 *
 * This function takes a list of parent candidates (toggle buttons with their Y coordinates)
 * and builds a hierarchy based on indentation levels (X coordinates).
 *
 * @param candidates List of (component, Y coordinate) pairs representing potential parents.
 * @param startX The X coordinate of the child component to start building hierarchy from.
 * @return List of parent texts, ordered from top-most to closest parent.
 */
private fun <T : JToggleButton> buildHierarchyFromCandidates(
    candidates: MutableList<Pair<T, Int>>,
    startX: Int
): List<String> {
    return buildComponentHierarchyFromCandidates(candidates, startX).mapNotNull { component ->
        component.text?.removeHtmlTags()?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Builds a hierarchy of parent components from sorted candidates.
 *
 * This function takes a list of parent candidates (toggle buttons with their Y coordinates)
 * and builds a hierarchy based on indentation levels (X coordinates).
 *
 * @param candidates List of (component, Y coordinate) pairs representing potential parents.
 * @param startX The X coordinate of the child component to start building hierarchy from.
 * @return List of parent components, ordered from top-most to closest parent.
 */
private fun <T : JToggleButton> buildComponentHierarchyFromCandidates(
    candidates: MutableList<Pair<T, Int>>,
    startX: Int
): List<T> {
    if (candidates.isEmpty()) return emptyList()

    // Sort by Y coordinate (top to bottom)
    candidates.sortBy { it.second }

    // Build the hierarchy by walking from closest to farthest parent
    val result = mutableListOf<T>()
    var currentX = startX

    for ((component, _) in candidates.reversed()) {
        val componentX = getAbsoluteX(component)
        if (componentX < currentX - LayoutConstants.MIN_INDENT_DIFF) {
            val text = component.text?.removeHtmlTags()?.trim()
            if (!text.isNullOrEmpty()) {
                result.add(0, component)
                currentX = componentX
            }
        }
    }

    return result
}

/**
 * Finds all components of a specific type within a container using lazy sequence.
 */
private inline fun <reified T : Component> findAllComponentsOfType(container: Container): Sequence<T> {
    return findComponentsSequence(container, T::class.java)
}

/**
 * Internal helper for recursive sequence generation (non-inline to allow recursion).
 */
@Suppress("UNCHECKED_CAST")
private fun <T : Component> findComponentsSequence(container: Container, targetClass: Class<T>): Sequence<T> =
    sequence {
        for (comp in container.components) {
            if (targetClass.isInstance(comp)) yield(comp as T)
            if (comp is Container) yieldAll(findComponentsSequence(comp, targetClass))
        }
    }

/**
 * Gets the absolute coordinate of a component on screen using the provided extractor.
 *
 * First attempts to use locationOnScreen (which requires the component to be showing).
 * Falls back to manually summing coordinates up the parent hierarchy.
 *
 * @param component The component to get the coordinate for.
 * @param screenCoordinate Extracts the coordinate from a Point (e.g., Point::x or Point::y).
 * @param localCoordinate Extracts the local coordinate from a Component (e.g., Component::getX or Component::getY).
 */
private inline fun getAbsoluteCoordinate(
    component: Component,
    screenCoordinate: (java.awt.Point) -> Int,
    localCoordinate: (Component) -> Int
): Int {
    return runCatching {
        screenCoordinate(component.locationOnScreen)
    }.getOrElse {
        var sum = 0
        var current: Component? = component
        while (current != null) {
            sum += localCoordinate(current)
            current = current.parent
        }
        sum
    }
}

private fun getAbsoluteY(component: Component): Int =
    getAbsoluteCoordinate(component, { it.y }, { it.y })

private fun getAbsoluteX(component: Component): Int =
    getAbsoluteCoordinate(component, { it.x }, { it.x })
