package com.intellij.plugin.copyOptionPath

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.newEditor.SettingsDialog
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.border.IdeaTitledBorder
import com.intellij.ui.navigation.History
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import java.lang.reflect.Field
import javax.swing.JPanel
import javax.swing.SwingUtilities

val LOG: Logger = Logger.getInstance("#com.intellij.plugin.copyOptionPath")

/**
 * Gets the settings path directly from SettingsEditor via component hierarchy.
 * This is the modern approach (2022+) similar to IntelliJ's CopySettingsPathAction.
 */
fun getPathFromSettingsEditor(component: Component): Collection<String>? {
    try {
        // Find SettingsEditor in component hierarchy using class name to avoid direct dependency
        var current: Component? = component
        var settingsEditor: Component? = null

        while (current != null) {
            if (current.javaClass.name == "com.intellij.openapi.options.newEditor.SettingsEditor") {
                settingsEditor = current
                break
            }
            current = current.parent
        }

        if (settingsEditor == null) {
            LOG.debug("SettingsEditor not found in component hierarchy")
            return null
        }

        LOG.debug("Found SettingsEditor: ${settingsEditor.javaClass.name}")

        // Call getPathNames() via reflection (it's package-private)
        val pathNamesMethod = settingsEditor.javaClass.getDeclaredMethod("getPathNames")
        pathNamesMethod.isAccessible = true
        @Suppress("UNCHECKED_CAST") val pathNames = pathNamesMethod.invoke(settingsEditor) as? Collection<String>

        LOG.debug("getPathNames() returned: $pathNames")
        return pathNames
    } catch (e: Exception) {
        LOG.warn("Error getting path from SettingsEditor: ${e.message}", e)
        return null
    }
}

fun appendTreePath(treePath: Array<out Any>, path: StringBuilder) {

    treePath.forEach {
        val pathStr = it.toString()
        if (StringUtil.isEmpty(pathStr)) {
            try {
                val textField = it.javaClass.getDeclaredField("myText")
                if (textField != null) {
                    textField.isAccessible = true
                    val textValue = textField.get(it)?.toString()
                    appendItem(path, textValue)
                }
            } catch (e: Exception) {
                LOG.debug("Error trying to get 'myText' field from $it: ${e.message}")
            }
        } else appendItem(path, pathStr)
    }
}

fun detectRowFromMousePoint(treeTable: TreeTable, e: AnActionEvent): Int {
    val point = getConvertedMousePoint(e, treeTable) ?: return -1
    val rowAtPoint = treeTable.rowAtPoint(point)
    return if (rowAtPoint <= treeTable.rowCount) rowAtPoint else -1
}

fun getConvertedMousePoint(event: AnActionEvent, destination: Component): Point? {
    val e = event.inputEvent
    if (e is MouseEvent) {
        return SwingUtilities.convertMouseEvent(e.component, e, destination).point
    }
    return null
}

fun getMiddlePath(src: Component, path: StringBuilder) {
    val jbTabs = UIUtil.getParentOfType(JBTabs::class.java, src)
    if (jbTabs != null) {
        val text = jbTabs.selectedInfo?.text
        if (StringUtil.isNotEmpty(text)) path.append("$text | ")
    }
    val parent = src.parent
    val title: String
    if (parent is JPanel) {
        val border = parent.border
        title = if (border is IdeaTitledBorder) border.title else ""
        if (StringUtil.isNotEmpty(title)) appendItem(path, title)
    }
}

fun appendItem(path: StringBuilder, item: String?) {
    if (StringUtil.isNotEmpty(item) && !path.trimEnd { c -> c == ' ' || c == '|' }
            .endsWith(item!!)) path.append("$item | ")
}

fun appendSrcText(path: StringBuilder, text: String?) {
    if (StringUtil.isNotEmpty(text)) path.append(text)
}

fun trimFinalResult(path: StringBuilder): String {
    val text = path.toString().trimEnd { c -> c == '|' || c == ' ' }
    return text.deleteAnyTag()
}

fun getInheritedPrivateField(type: Class<Any>, name: String, orClassName: String?): Field? {
    var i: Class<Any> = type
    while (i != Any::class.java) {
        for (f in i.declaredFields) {
            if (name == f.name || (orClassName != null && f.type.name == orClassName)) return f
        }
        i = i.getSuperclass()
    }

    return null
}

fun getPathFromSettingsDialog(settings: SettingsDialog): String? {
    try {
        // Try new API first (2022+): SettingsEditor.getPathNames()
        val editor = settings.editor
        val editorClassName = editor.javaClass.name
        LOG.debug("Editor class: $editorClassName")

        // getPathNames() only exists on SettingsEditor, not on SingleSettingEditor or AbstractEditor
        if (!editorClassName.contains("SettingsEditor")) {
            LOG.debug("Editor is not SettingsEditor, falling back to legacy approach")
            return getPathFromSettingsDialogLegacy(settings)
        }

        // getPathNames() is package-private, so we need getDeclaredMethod
        val pathNamesMethod = editor.javaClass.getDeclaredMethod("getPathNames")
        pathNamesMethod.isAccessible = true
        @Suppress("UNCHECKED_CAST") val pathNames = pathNamesMethod.invoke(editor) as? Collection<String>
        LOG.debug("pathNames result: $pathNames")

        if (pathNames != null && pathNames.isNotEmpty()) {
            return "Settings | " + pathNames.joinToString(" | ")
        }
        return "Settings"
    } catch (e: NoSuchMethodException) {
        // Fall back to legacy reflection-based approach for older IDEs
        LOG.warn("getPathNames method not found, falling back to legacy approach: ${e.message}")
        return getPathFromSettingsDialogLegacy(settings)
    } catch (e: Exception) {
        LOG.warn("Exception when getting path from settings dialog: ${e.message}", e)
        return getPathFromSettingsDialogLegacy(settings)
    }
}

private fun getPathFromSettingsDialogLegacy(settings: SettingsDialog): String? {
    try {
        // Try both old field name "myEditor" and new field name "editor"
        var editorField = getInheritedPrivateField(
            settings.javaClass, "myEditor", "com.intellij.openapi.options.newEditor.AbstractEditor"
        )
        if (editorField == null) {
            editorField = getInheritedPrivateField(
                settings.javaClass, "editor", "com.intellij.openapi.options.newEditor.AbstractEditor"
            )
        }
        if (editorField == null) {
            LOG.warn("Could not find editor field in SettingsDialog")
            return null
        }
        editorField.isAccessible = true
        val settingsEditorInstance = editorField.get(settings) as? JPanel ?: return ""
        val banner = getInheritedPrivateField(
            settingsEditorInstance.javaClass, "myBanner", "com.intellij.openapi.options.newEditor.Banner"
        ) ?: getInheritedPrivateField(
            settingsEditorInstance.javaClass,
            "myBanner",
            "com.intellij.openapi.options.newEditor.ConfigurableEditorBanner"
        )
        if (banner == null) {
            LOG.warn("Could not find banner field in editor")
            return null
        }
        banner.isAccessible = true
        val bannerInstance = banner.get(settingsEditorInstance)
        val bk = getInheritedPrivateField(
            bannerInstance.javaClass, "myBreadcrumbs", "com.intellij.ui.components.breadcrumbs.Breadcrumbs"
        )
        if (bk == null) {
            LOG.warn("Could not find myBreadcrumbs field in banner")
            return null
        }
        bk.isAccessible = true
        val bkViews = bk.type.getDeclaredField("views")
        bkViews.isAccessible = true
        val bkInst = bk.get(bannerInstance)
        val views = bkViews.get(bkInst) as? ArrayList<*>
        var path = "Settings | "
        views?.forEachIndexed { i, cr ->
            val text = cr.javaClass.getDeclaredField("text")
            text.isAccessible = true
            val value = text.get(cr)
            if (value != null) path += (if (i > 0) " | " else "") + value
        }
        return path

    } catch (e: Exception) {
        LOG.warn("Exception when appending path (legacy): ${e.message}", e)
        return ""
    }
}

fun appendPathFromProjectStructureDialog(configurable: Configurable, path: StringBuilder) {
    try {
        // Use the configurable's classloader to find ProjectStructureConfigurable
        val cfg = Class.forName(
            "com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable",
            true,
            configurable.javaClass.classLoader
        )
        if (cfg.isInstance(configurable)) {
            // Get the current category configurable
            var categoryConfigurable: Configurable? = null
            for (f in cfg.declaredFields) {
                if ("myHistory" == f.name || f.type.name == "com.intellij.ui.navigation.History") {
                    f.isAccessible = true
                    val history = f.get(configurable) as? History ?: continue
                    val place = history.query()
                    categoryConfigurable = place.getPath("category") as? Configurable
                    break
                }
            }
            
            if (categoryConfigurable == null) return
            
            // Try to get the separator (section name) from SidePanel
            val sectionName = getSidePanelSeparatorForConfigurable(configurable, cfg, categoryConfigurable)
            if (sectionName != null && sectionName != "--") {
                appendItem(path, sectionName)
            }
            
            // Add the category name (e.g., "Project", "Modules")
            appendItem(path, categoryConfigurable.displayName)
        }
    } catch (e: ClassNotFoundException) {
        LOG.debug("ProjectStructureConfigurable not available: " + e.message)
    } catch (e: Exception) {
        LOG.warn("Can not get project structure path: " + e.message)
    }
}

/**
 * Gets the separator text (section name like "Project Settings") for a given configurable
 * from the ProjectStructureConfigurable's SidePanel.
 */
private fun getSidePanelSeparatorForConfigurable(
    projectStructureConfigurable: Any,
    cfgClass: Class<*>,
    categoryConfigurable: Configurable
): String? {
    try {
        // Get mySidePanel field
        val sidePanelField = cfgClass.getDeclaredField("mySidePanel")
        sidePanelField.isAccessible = true
        val sidePanel = sidePanelField.get(projectStructureConfigurable) ?: return null
        
        // Get myModel (DefaultListModel) from SidePanel
        val modelField = sidePanel.javaClass.getDeclaredField("myModel")
        modelField.isAccessible = true
        val model = modelField.get(sidePanel) as? javax.swing.DefaultListModel<*> ?: return null
        
        // Get myIndex2Separator map from SidePanel
        val separatorField = sidePanel.javaClass.getDeclaredField("myIndex2Separator")
        separatorField.isAccessible = true
        val separatorMap = separatorField.get(sidePanel)
        
        // Find the index of the current configurable in the model
        var itemIndex = -1
        for (i in 0 until model.size) {
            val item = model.getElementAt(i)
            // SidePanelItem has myPlace field which contains the category
            val placeField = item.javaClass.getDeclaredField("myPlace")
            placeField.isAccessible = true
            val place = placeField.get(item) as? com.intellij.ui.navigation.Place
            val itemCategory = place?.getPath("category") as? Configurable
            if (itemCategory === categoryConfigurable || itemCategory?.displayName == categoryConfigurable.displayName) {
                itemIndex = i
                break
            }
        }
        
        if (itemIndex < 0) return null
        
        // Find the separator that applies to this index (highest separator index <= item index)
        // The separatorMap is an Int2ObjectMap, so we need to iterate through possible indices
        var bestSeparator: String? = null
        var bestSeparatorIndex = -1
        
        // Try to get separator using the map's get method
        val getMethod = separatorMap.javaClass.getMethod("get", Int::class.javaPrimitiveType)
        for (i in 0..itemIndex) {
            val separator = getMethod.invoke(separatorMap, i) as? String
            if (separator != null) {
                bestSeparator = separator
                bestSeparatorIndex = i
            }
        }
        
        return bestSeparator
    } catch (e: Exception) {
        LOG.debug("Could not get SidePanel separator: ${e.message}")
        return null
    }
}

private fun String.deleteTag(tag: String): String {
    return this.replace("<$tag>", "").replace("</$tag>", "")
}

private fun String.deleteAnyTag(): String {
    return this.replace(Regex("<[^>]*>"), "")
}
