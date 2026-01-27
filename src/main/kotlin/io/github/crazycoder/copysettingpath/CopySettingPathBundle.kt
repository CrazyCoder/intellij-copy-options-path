package io.github.crazycoder.copysettingpath

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.CopySettingPathBundle"

/**
 * Resource bundle for Copy Setting Path plugin messages.
 */
object CopySettingPathBundle : DynamicBundle(BUNDLE) {

    @JvmStatic
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any
    ): String = getMessage(key, *params)
}
