package io.github.crazycoder.copysettingpath

import com.intellij.openapi.diagnostic.Logger
import java.awt.Component

/**
 * Core utilities and constants for the Copy Setting Path plugin.
 *
 * This file contains:
 * - Logger instance
 * - Path-related constants
 * - Cached regex patterns
 * - Layout constants (magic numbers extracted)
 * - Common string manipulation functions
 */

/** Logger instance for the Copy Setting Path plugin. */
val LOG: Logger = Logger.getInstance("#io.github.crazycoder.copysettingpath")

/**
 * Ids of the advanced settings this plugin declares.
 *
 * They must match the `advancedSetting` ids in plugin.xml. Keeping them together makes that
 * comparison a single glance, instead of hunting through the files that read them.
 */
object AdvancedSettingIds {
    const val MOUSE_INTERCEPT = "copy.setting.path.mouse.intercept"
    const val INCLUDE_ADJACENT_VALUE = "copy.setting.path.include.adjacent.value"
    const val SHOW_BALLOON = "copy.setting.path.show.balloon"
    const val ANIMATE_NOTIFICATION = "copy.setting.path.animate.notification"
    const val PLAY_SOUND = "copy.setting.path.play.sound"
    const val NOTIFICATION_DELAY = "copy.setting.path.notification.delay"
    const val SEPARATOR = "copy.setting.path.separator"
}

/**
 * Constants used throughout the plugin for path construction and reflection.
 */
object PathConstants {
    /** Separator used between path components. */
    const val SEPARATOR = " | "

    /** Placeholder separator in Project Structure that should be ignored. */
    const val IGNORED_SEPARATOR = "--"

    // Class names used for reflection (to avoid direct dependencies)
    const val SETTINGS_EDITOR_CLASS = "com.intellij.openapi.options.newEditor.SettingsEditor"
    const val CONFIGURABLE_EDITOR_CLASS = "com.intellij.openapi.options.newEditor.ConfigurableEditor"
    const val SETTINGS_TREE_VIEW_CLASS = "com.intellij.openapi.options.newEditor.SettingsTreeView"
    const val PROJECT_STRUCTURE_CONFIGURABLE_CLASS =
        "com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable"

    // Popup class names (for JBPopup-based floating dialogs)
    const val FIND_POPUP_PANEL_CLASS = "com.intellij.find.impl.FindPopupPanel"
    const val SEARCH_EVERYWHERE_UI_CLASS = "com.intellij.ide.actions.searcheverywhere.SearchEverywhereUI"
    const val BIG_POPUP_UI_CLASS = "com.intellij.ide.actions.BigPopupUI"

    /**
     * Names the Switcher panel has had.
     *
     * It moved from `com.intellij.ide.actions` to the recent-files frontend module. Listing
     * every known name keeps one build working across the branches that disagree, so a move
     * costs a list entry instead of a release that breaks the other branch. Apply the same
     * pattern to any other class that turns out to move.
     */
    val SWITCHER_PANEL_CLASSES = listOf(
        "com.intellij.platform.recentFiles.frontend.Switcher\$SwitcherPanel",
        "com.intellij.ide.actions.Switcher\$SwitcherPanel",
    )

    // Field names used for reflection
    const val FIELD_MY_TEXT = "myText"
    const val FIELD_MY_HISTORY = "myHistory"
    const val FIELD_MY_SIDE_PANEL = "mySidePanel"
    const val FIELD_MY_MODEL = "myModel"
    const val FIELD_MY_INDEX_2_SEPARATOR = "myIndex2Separator"
    const val FIELD_MY_PLACE = "myPlace"

    // Navigation place keys
    const val PLACE_CATEGORY = "category"

    // Method names
    const val METHOD_GET_PATH_NAMES = "getPathNames"
    const val METHOD_GET = "get"
}

/**
 * Layout-related constants extracted from magic numbers.
 * These control spatial analysis for component positioning.
 */
object LayoutConstants {
    /** Maximum height for value components (excludes large panels/text areas). */
    const val MAX_VALUE_COMPONENT_HEIGHT = 80

    /** Maximum width for value components (excludes large panels). */
    const val MAX_VALUE_COMPONENT_WIDTH = 400

    /** Tolerance for row alignment (center Y difference). */
    const val ROW_ALIGNMENT_TOLERANCE = 5
}

/**
 * Cached regex patterns for better performance.
 * Regex compilation is expensive, so we cache patterns that are used frequently.
 */
object RegexPatterns {
    /** Pattern to match HTML tags for removal. */
    val HTML_TAGS: Regex = Regex("<[^>]*>")

    /** Line breaks, which separate words and so become a space rather than nothing. */
    val HTML_LINE_BREAK: Regex = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    /**
     * Pattern to match Advanced Settings ID display in HTML labels.
     * In Advanced Settings, the setting ID is shown after a <br> tag, e.g.:
     * <html>Label text<br><pre><font...>setting.id.here</font>...</html>
     * This pattern removes the <br> and everything after it. It requires the <pre> that
     * Advanced Settings emits, so ordinary multi-line labels keep their remaining lines.
     */
    val HTML_SETTING_ID_SUFFIX: Regex = Regex("<br>\\s*<pre.*", RegexOption.DOT_MATCHES_ALL)

    /** Pattern to match Advanced Settings IDs appended to labels (requires colon separator). */
    val ADVANCED_SETTING_ID: Regex = Regex(":[a-z][a-z0-9]*(?:\\.[a-z0-9]+)+$")

    /** Pattern to detect default toString() object references (e.g., "ClassName@hexAddress"). */
    val OBJECT_REFERENCE: Regex = Regex(".*@[0-9a-fA-F]+$")

    /** Pattern to match multiple whitespace characters (spaces, tabs, newlines) for collapsing. */
    val MULTIPLE_WHITESPACE: Regex = Regex("\\s+")
}

// ============================================================================
// String Extension Functions
// ============================================================================

/**
 * Whether the string is HTML as Swing understands it.
 *
 * Swing renders a label as HTML only when its text starts with the html tag. Without that
 * prefix the angle brackets are shown literally, so they belong to the value and must be kept.
 * Settings are full of such values: `<default>`, `<no scheme>`, `<Project>`, `List<String>`.
 */
private fun String.isSwingHtml(): Boolean = trimStart().startsWith("<html", ignoreCase = true)

/**
 * Removes all HTML tags from a string and collapses whitespace.
 *
 * This function:
 * 1. Leaves text that is not Swing HTML alone, so angle brackets in a value survive
 * 2. Removes Advanced Settings ID suffixes (shown after <br> in Advanced Settings labels)
 * 3. Turns line breaks into spaces, so the words around them do not run together
 * 4. Removes all HTML tags
 * 5. Collapses multiple whitespace characters (spaces, tabs, newlines) into a single space
 * 6. Trims leading and trailing whitespace
 *
 * Uses cached regex patterns for performance.
 */
fun String.removeHtmlTags(): String {
    if (!isSwingHtml()) return collapseWhitespace()
    return this
        .replace(RegexPatterns.HTML_SETTING_ID_SUFFIX, "")
        .replace(RegexPatterns.HTML_LINE_BREAK, " ")
        .replace(RegexPatterns.HTML_TAGS, "")
        .collapseWhitespace()
}

/**
 * Collapses runs of whitespace into a single space and trims the ends.
 */
private fun String.collapseWhitespace(): String =
    replace(RegexPatterns.MULTIPLE_WHITESPACE, " ").trim()

/**
 * Removes Advanced Settings IDs that may be appended to labels.
 *
 * Pattern: "Label text:setting.id.here" -> "Label text"
 * The ID pattern is: colon followed by a dotted identifier (e.g., "copy.setting.path.separator").
 */
private fun String.removeAdvancedSettingIds(): String =
    replace(RegexPatterns.ADVANCED_SETTING_ID, "")

// ============================================================================
// Path Building Functions
// ============================================================================

/**
 * Appends an item to the path if it's not empty and not already the last item.
 *
 * @param path StringBuilder to append to.
 * @param item The item to append.
 * @param separator The separator to use between path components.
 * @param allowDuplicate If true, allows appending even if it matches the last segment.
 *                       Useful for tree paths where parent and child can have the same name.
 */
fun appendItem(
    path: StringBuilder,
    item: String?,
    separator: String = PathConstants.SEPARATOR,
    allowDuplicate: Boolean = false
) {
    if (item.isNullOrEmpty()) return
    val cleanItem = item.removeHtmlTags()
    if (cleanItem.isEmpty()) return

    if (!allowDuplicate) {
        // Check for exact segment match (not just suffix match)
        val trimmedPath = path.toString().trimTrailingSeparators(separator)
        val lastSegment = trimmedPath.substringAfterLast(separator).trim()
        if (lastSegment == cleanItem) return
    }

    path.append(cleanItem)
    // If the item ends with ":", it acts as a natural grouping label.
    path.append(if (cleanItem.endsWith(":")) " " else separator)
}

/**
 * Removes trailing separators and whitespace from a path.
 *
 * Only the separator that is actually in use is removed, and only as a whole. Trimming the
 * set of characters used by every separator style would eat characters that belong to a value,
 * turning "<default>" into "<default" and "List<String>" into "List<String".
 *
 * @param separator The separator in use.
 */
private fun String.trimTrailingSeparators(separator: String): String {
    var result = this
    while (separator.isNotEmpty() && result.endsWith(separator)) {
        result = result.dropLast(separator.length)
    }
    return result.trimEnd()
}

/**
 * Trims the final result by removing trailing separators, HTML tags, and Advanced Settings IDs.
 *
 * @param path The path StringBuilder to process.
 * @param separator The separator in use.
 * @return The cleaned path string.
 */
fun trimFinalResult(path: StringBuilder, separator: String = PathConstants.SEPARATOR): String {
    return path.toString()
        .trimTrailingSeparators(separator)
        .removeHtmlTags()
        .removeAdvancedSettingIds()
}

// ============================================================================
// Component Position Functions
// ============================================================================

/**
 * Gets the absolute Y coordinate of a component on screen.
 *
 * @param component The component to get the Y coordinate for.
 * @return The absolute Y coordinate on screen.
 */
fun getAbsoluteY(component: Component): Int =
    runCatching { component.locationOnScreen.y }.getOrDefault(component.y)
