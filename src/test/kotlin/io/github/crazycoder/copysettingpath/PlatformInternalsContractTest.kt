package io.github.crazycoder.copysettingpath

import com.intellij.openapi.options.ex.Settings
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts that every IntelliJ internal the plugin reaches for by name still exists.
 *
 * The plugin compiles against the oldest supported IDE but has to run on much newer ones, so the
 * compiler cannot check these names. Without this test a rename is only discovered when a user
 * reports that the plugin stopped working, which is how the 2026.2 breakage was found.
 *
 * Run it against a newer IDE than the compile target with the `test20261` and `test20262` tasks.
 * A failure here names the exact identifier that moved.
 *
 * Classes are loaded without initialising them, so no Application is needed.
 */
class PlatformInternalsContractTest {

    private fun loadOrNull(className: String): Class<*>? =
        runCatching { Class.forName(className, false, javaClass.classLoader) }.getOrNull()

    private fun load(className: String): Class<*> =
        loadOrNull(className) ?: error("Platform class is gone: $className")

    /**
     * Names the platform the run is actually testing.
     *
     * Without this a failure gives no clue which IDE produced it, and a misconfigured testIde
     * task that silently falls back to the compile target would look like a passing run.
     */
    @Test
    fun `report the platform under test`() {
        val home = com.intellij.openapi.application.PathManager.getHomePath()
        val build = java.io.File(home, "build.txt").takeIf { it.isFile }?.readText()?.trim()
        // Present since 2026.2, absent before, so it tells the two eras apart at a glance
        val nonModalSettings = loadOrNull("com.intellij.openapi.options.newEditor.SettingsNonModalDialog") != null
        println(
            "Platform under test: build=$build nonModalSettings=$nonModalSettings " +
                    "java=${System.getProperty("java.version")} home=$home"
        )
        assertNotNull("Cannot tell which platform is on the test classpath", build ?: home)
    }

    @Test
    fun `settings classes resolve`() {
        load(PathConstants.SETTINGS_EDITOR_CLASS)
        load(PathConstants.CONFIGURABLE_EDITOR_CLASS)
        load(PathConstants.SETTINGS_TREE_VIEW_CLASS)
    }

    @Test
    fun `popup classes resolve`() {
        load(PathConstants.FIND_POPUP_PANEL_CLASS)
        load(PathConstants.SEARCH_EVERYWHERE_UI_CLASS)
        load(PathConstants.BIG_POPUP_UI_CLASS)
    }

    /**
     * The Switcher panel has moved package before, so any one of the known names is enough.
     * If this fails, find the new name and add it to the list rather than replacing it.
     */
    @Test
    fun `at least one switcher panel name resolves`() {
        val resolved = PathConstants.SWITCHER_PANEL_CLASSES.filter { loadOrNull(it) != null }
        assertTrue(
            "None of the known Switcher panel names resolve: ${PathConstants.SWITCHER_PANEL_CLASSES}",
            resolved.isNotEmpty()
        )
    }

    /**
     * The Settings breadcrumb comes from this method. It is package-private, so it is reached
     * with findDeclaredMethod rather than getMethod.
     */
    @Test
    fun `SettingsEditor still declares getPathNames`() {
        val settingsEditor = load(PathConstants.SETTINGS_EDITOR_CLASS)
        val method = findDeclaredMethod(settingsEditor, PathConstants.METHOD_GET_PATH_NAMES)
        assertNotNull(
            "${PathConstants.SETTINGS_EDITOR_CLASS}.${PathConstants.METHOD_GET_PATH_NAMES}() is gone",
            method
        )
        assertTrue(
            "getPathNames() no longer returns a Collection",
            Collection::class.java.isAssignableFrom(method!!.returnType)
        )
    }

    /**
     * The Settings window is detected with this public data key rather than by class name,
     * which is why the non-modal window introduced in 2026.2 needed no detection change.
     */
    @Test
    fun `Settings data key is available`() {
        assertNotNull(Settings.KEY)
    }

    /**
     * Project Structure lives in the Java plugin, so it is absent from a bare platform test
     * classpath and from IDEs that do not ship it. Only its shape is asserted, when present.
     */
    @Test
    fun `project structure internals resolve when the class is present`() {
        val configurable = loadOrNull(PathConstants.PROJECT_STRUCTURE_CONFIGURABLE_CLASS) ?: return
        val fields = configurable.declaredFields.map { it.name }
        for (field in listOf(PathConstants.FIELD_MY_HISTORY, PathConstants.FIELD_MY_SIDE_PANEL)) {
            assertTrue("${configurable.name}.$field is gone", field in fields)
        }
    }
}
