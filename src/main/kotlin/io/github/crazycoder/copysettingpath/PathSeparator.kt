package io.github.crazycoder.copysettingpath

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.CopySettingPathBundle"

/**
 * Enum representing available path separator styles for the Copy Setting Path action.
 * Each separator defines how path components are joined in the copied result.
 *
 * The entries are chosen by the user through the "copy.setting.path.separator" advanced
 * setting, which names this class in plugin.xml, so nothing references them from code.
 *
 * @property separator The actual separator string used between path components.
 */
@Suppress("unused")
enum class PathSeparator(val separator: String) {
    PIPE(" | "),
    ARROW(" > "),
    UNICODE_ARROW(" → "),
    GUILLEMET(" » "),
    TRIANGLE(" ▸ ");

    @Nls
    override fun toString(): String = message("path.separator.${name.lowercase()}")

    companion object {
        private val bundle = DynamicBundle(PathSeparator::class.java, BUNDLE)

        @Nls
        private fun message(@PropertyKey(resourceBundle = BUNDLE) key: String): String = bundle.getMessage(key)
    }
}
