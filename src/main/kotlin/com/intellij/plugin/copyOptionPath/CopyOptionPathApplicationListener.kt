package com.intellij.plugin.copyOptionPath

import com.intellij.ide.ApplicationInitializedListener

/**
 * Initializes the MouseEventInterceptor when the application starts.
 *
 * This ensures that Ctrl+Click (or Cmd+Click on macOS) events are intercepted
 * before they can activate UI components like checkboxes, while still allowing
 * the CopyOptionsPath action to trigger.
 *
 * Using ApplicationInitializedListener ensures early initialization, before any
 * projects are opened and before Settings dialogs can be shown.
 */
class CopyOptionPathApplicationListener : ApplicationInitializedListener {

    override suspend fun execute() {
        // Register the mouse event interceptor at application startup
        MouseEventInterceptor.getInstance().register()
    }
}
