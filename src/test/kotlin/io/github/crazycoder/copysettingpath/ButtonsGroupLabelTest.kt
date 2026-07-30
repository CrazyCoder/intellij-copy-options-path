package io.github.crazycoder.copysettingpath

import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.dsl.builder.panel
import io.github.crazycoder.copysettingpath.path.ComponentLabelExtractor
import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Test
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton

/**
 * Covers the title of a vertical Kotlin UI DSL buttons group.
 *
 * `buttonsGroup("Title:")` puts the title on a row of its own above the buttons and connects the
 * two by nothing but the layout, so the title used to be missing from the path. The cases below
 * are the two that were reported, plus the neighbours that must keep their own label: a label
 * that titles nothing, and a group that sits next to another column.
 */
class ButtonsGroupLabelTest {

    companion object {
        @JvmField
        @ClassRule
        val application: ApplicationRule = ApplicationRule()
    }

    /**
     * The label the plugin builds the path from, per button text, for one sample page.
     */
    private val labels: Map<String, String?> by lazy {
        var result: Map<String, String?> = emptyMap()
        runInEdtAndWait { result = buildLabels() }
        result
    }

    private fun buildLabels(): Map<String, String?> {
        val page = panel {
            group("Files") {
                row { checkBox("Back up files before saving") }
                buttonsGroup("Sync external changes:") {
                    row { checkBox("When switching to the IDE window") }
                    row { checkBox("Periodically when the IDE is inactive") }
                }
            }
            group("Closing Policy") {
                row("Tab limit:") { intTextField() }
                buttonsGroup("When tabs exceed the limit:") {
                    row { radioButton("Close unchanged") }
                    row { radioButton("Close unused") }
                }
            }
            row { label("A note without a colon") }
            indent {
                row { checkBox("Indented under a colonless label") }
            }
            row {
                panel {
                    row { label("Left column:") }
                    indent { row { checkBox("Left column member") } }
                }
                panel {
                    row { checkBox("Right column member") }
                }
            }
        }

        return buttons(page).associate { it.text to ComponentLabelExtractor.getComponentLabel(it) }
    }

    private fun buttons(component: Component): List<AbstractButton> =
        when (component) {
            is AbstractButton -> listOf(component)
            is Container -> component.components.flatMap { buttons(it) }
            else -> emptyList()
        }

    @Test
    fun `checkboxes take the title of the group above them`() {
        assertEquals("Sync external changes:", labels["When switching to the IDE window"])
        assertEquals("Sync external changes:", labels["Periodically when the IDE is inactive"])
    }

    @Test
    fun `radio buttons take the title of the group above them`() {
        assertEquals("When tabs exceed the limit:", labels["Close unchanged"])
        assertEquals("When tabs exceed the limit:", labels["Close unused"])
    }

    @Test
    fun `a button outside a group keeps its own label`() {
        assertEquals("Back up files before saving", labels["Back up files before saving"])
    }

    /**
     * An indent alone does not make a group. Without the colon the label above is prose, and the
     * comment under a group is written exactly like this.
     */
    @Test
    fun `an indented button under a label without a colon keeps its own label`() {
        assertEquals("Indented under a colonless label", labels["Indented under a colonless label"])
    }

    /**
     * Each column is a grid of its own, so a group in one column cannot reach the title of the
     * column beside it. This is the false positive a purely geometric search would produce.
     */
    @Test
    fun `a group does not take the title of the column beside it`() {
        assertEquals("Left column:", labels["Left column member"])
        assertEquals("Right column member", labels["Right column member"])
    }
}
