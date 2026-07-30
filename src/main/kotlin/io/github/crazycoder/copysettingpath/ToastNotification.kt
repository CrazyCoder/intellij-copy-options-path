package io.github.crazycoder.copysettingpath

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Component
import java.awt.MouseInfo
import java.awt.Point
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent
import javax.swing.Timer

/**
 * Toast notification system for the Copy Setting Path plugin.
 * Provides visual feedback when a path is copied to clipboard.
 *
 * Features:
 * - Rounded toast popup with checkmark icon
 * - Optional rise animation
 * - Optional sequential path segment highlighting
 * - Configurable display duration
 * - Sound notification
 */

// ============================================================================
// Settings Constants
// ============================================================================

/** Default notification delay in seconds. */
private const val DEFAULT_NOTIFICATION_DELAY_SECONDS = 1F

// ============================================================================
// Animation Constants
// ============================================================================

/** Fade-in animation duration in milliseconds. */
private const val FADE_IN_DURATION_MS = 150

/** Fade-out animation duration in milliseconds. */
private const val FADE_OUT_DURATION_MS = 200

/** Animation timer step interval in milliseconds. */
private const val ANIMATION_STEP_MS = 15

/**
 * How far below its resting place the toast starts, in pixels.
 *
 * Small on purpose. The rise says "this just arrived", nothing more. Pointing at what was
 * copied is the highlight sweep's job, and it can do that only once the text stops moving.
 */
private const val RISE_START_OFFSET = 8

/** Rise animation duration in milliseconds. */
private const val RISE_DURATION_MS = 140

/** How long the sweep waits before moving on to the next segment, in milliseconds. */
private const val SWEEP_SEGMENT_DELAY_MS = 110

/**
 * How long a segment takes to fade out after the sweep has passed it, in milliseconds.
 *
 * Longer than [SWEEP_SEGMENT_DELAY_MS] on purpose: segments the sweep has already passed are
 * still fading while the next ones light up, which is what makes it read as a trail rather than
 * as a single block moving along.
 */
private const val SWEEP_TRAIL_FADE_MS = 320

/**
 * Longest the sweep may take to reach the last segment, in milliseconds.
 *
 * Long paths would otherwise still be sweeping when the toast starts to fade out. Past this
 * budget the sweep moves faster instead of running out of time.
 */
private const val SWEEP_MAX_ADVANCE_MS = 600

/** Corner radius for the toast popup. */
private const val TOAST_CORNER_RADIUS = 12

/** Corner radius for segment highlight. */
private const val SEGMENT_CORNER_RADIUS = 8

/**
 * Alpha of a fully lit segment highlight, over the toast background (0-255).
 *
 * 70 lands at a contrast ratio of about 1.96 against the notification background in both the
 * light and the dark theme, which reads as a clear but soft highlight. The value is symmetric
 * across themes because the highlight is derived from the foreground colour.
 */
private const val HIGHLIGHT_ALPHA = 70

// ============================================================================
// Sound Constants
// ============================================================================

/** Volume reduction in decibels (negative = quieter). */
private const val SOUND_VOLUME_DB = -20f

// ============================================================================
// Public API
// ============================================================================

/**
 * Shows a brief toast notification near the mouse cursor
 * displaying the path that was copied to clipboard.
 *
 * The notification is only shown if the "copy.setting.path.show.balloon" advanced setting is enabled.
 *
 * @param copiedPath The path that was copied to clipboard, to display in the notification.
 * @param sourceComponent The component from which the path was copied (currently unused).
 */
@Suppress("UNUSED_PARAMETER")
fun showCopiedBalloon(copiedPath: String, sourceComponent: Component? = null) {
    if (!AdvancedSettings.getBoolean(AdvancedSettingIds.SHOW_BALLOON)) return

    val mouseLocation = MouseInfo.getPointerInfo()?.location ?: return

    showToast(copiedPath, mouseLocation)
}

// ============================================================================
// Private Implementation
// ============================================================================

/** Currently visible toast window, if any. */
@Volatile
private var currentToast: ToastWindow? = null

/**
 * Gets the notification delay in milliseconds from Advanced Settings.
 */
private fun getNotificationDelayMs(): Long {
    val delayString = AdvancedSettings.getString(AdvancedSettingIds.NOTIFICATION_DELAY)
    val delaySeconds = delayString.toFloatOrNull() ?: DEFAULT_NOTIFICATION_DELAY_SECONDS
    return (delaySeconds * 1000).toLong().coerceAtLeast(100L)
}

/**
 * Gets the currently configured path separator from Advanced Settings.
 */
private fun getConfiguredSeparator(): String =
    AdvancedSettings.getEnum(AdvancedSettingIds.SEPARATOR, PathSeparator::class.java).separator

/**
 * Plays a notification sound when a path is copied.
 * Only plays if the sound setting is enabled.
 */
private fun playNotificationSound() {
    if (!AdvancedSettings.getBoolean(AdvancedSettingIds.PLAY_SOUND)) return

    try {
        val soundStream = object {}.javaClass.getResourceAsStream("/sounds/switch.wav") ?: return
        val audioStream = AudioSystem.getAudioInputStream(soundStream.buffered())
        val clip = AudioSystem.getClip()
        clip.open(audioStream)

        // Reduce volume
        (clip.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl)?.value = SOUND_VOLUME_DB

        clip.addLineListener { event ->
            if (event.type == LineEvent.Type.STOP) {
                clip.close()
            }
        }
        clip.start()
    } catch (e: Exception) {
        LOG.warn("Failed to play notification sound", e)
    }
}

/**
 * Shows a custom toast notification at the given screen location.
 * Uses a heavyweight JWindow with setAlwaysOnTop to ensure it appears above menus/popups.
 * Features an optional rise animation and sequential path segment highlighting.
 * Only one toast is shown at a time - previous toasts are closed when a new one appears.
 */
private fun showToast(text: String, screenLocation: Point) {
    // Close any existing toast
    currentToast?.dispose()

    val separator = getConfiguredSeparator()
    val animationsEnabled = AdvancedSettings.getBoolean(AdvancedSettingIds.ANIMATE_NOTIFICATION)
    val toast = ToastWindow(text, separator)
    currentToast = toast

    // Position above the mouse cursor
    val toastSize = toast.preferredSize
    val x = screenLocation.x - toastSize.width / 2
    val targetY = screenLocation.y - toastSize.height - 10

    if (animationsEnabled) {
        // Start below target for the rise animation
        toast.setLocation(x, targetY + RISE_START_OFFSET)
        toast.setTargetY(targetY)
        toast.showWithRise()
    } else {
        // Simple fade-in without the rise
        toast.setLocation(x, targetY)
        toast.showWithFade()
    }

    playNotificationSound()

    // Auto-hide after delay with fade-out animation
    val delayMs = getNotificationDelayMs()
    AppExecutorUtil.getAppScheduledExecutorService().schedule(
        {
            ApplicationManager.getApplication().invokeLater {
                // Only fade out if this is still the current toast
                if (currentToast === toast) {
                    toast.fadeOutAndDispose()
                }
            }
        },
        delayMs,
        TimeUnit.MILLISECONDS
    )
}

// ============================================================================
// Toast Window Implementation
// ============================================================================

/**
 * A lightweight toast window that appears on top of all other windows.
 * Features a rise animation and sequential path segment highlighting.
 * Any mouse click anywhere dismisses it.
 * Also dismisses when another window is activated or a dialog opens.
 *
 * @param text The path text to display.
 * @param separator The separator string used to split the path into segments.
 */
private class ToastWindow(text: String, private val separator: String) : javax.swing.JWindow() {
    private var mouseHandler: java.awt.event.AWTEventListener? = null
    private var windowHandler: java.awt.event.AWTEventListener? = null
    private var animationTimer: Timer? = null
    private var highlightTimer: Timer? = null
    private val segmentLabels = mutableListOf<HighlightableLabel>()
    private var targetY: Int = 0
    private val normalBackground = com.intellij.util.ui.JBUI.CurrentTheme.NotificationInfo.backgroundColor()

    /**
     * Semi-transparent highlight colour derived from the theme's foreground colour.
     *
     * The foreground is the only colour guaranteed to contrast with the background in every
     * theme, including custom ones. The border colour is not: in Islands Dark the notification
     * border and background are both #33353B, so a highlight derived from the border blended to
     * exactly the background and the sweep was invisible.
     *
     * @param intensity How lit the segment is, from 0 for gone to 1 for the head of the sweep.
     */
    @Suppress("UseJBColor")
    private fun highlightColor(intensity: Float): java.awt.Color {
        val foreground = com.intellij.util.ui.JBUI.CurrentTheme.NotificationInfo.foregroundColor()
        val alpha = (HIGHLIGHT_ALPHA * intensity).toInt().coerceIn(0, HIGHLIGHT_ALPHA)
        return java.awt.Color(foreground.red, foreground.green, foreground.blue, alpha)
    }

    /**
     * Custom label that properly paints semi-transparent highlight backgrounds.
     * Shows a rounded highlight sweep effect.
     */
    private inner class HighlightableLabel(text: String) : javax.swing.JLabel(text) {
        /** How lit this segment is, from 0 for gone to 1 for the head of the sweep. */
        var highlightIntensity: Float = 0f
            set(value) {
                val clamped = value.coerceIn(0f, 1f)
                if (clamped == field) return
                field = clamped
                repaint()
            }

        init {
            isOpaque = false  // We'll paint background ourselves
            foreground = com.intellij.util.ui.JBUI.CurrentTheme.NotificationInfo.foregroundColor()
            border = javax.swing.BorderFactory.createEmptyBorder(3, 5, 3, 5)
        }

        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )
            // First fill with normal background to clear any previous content
            g2.color = normalBackground
            g2.fillRoundRect(0, 0, width, height, SEGMENT_CORNER_RADIUS, SEGMENT_CORNER_RADIUS)

            // Then paint the highlight on top, as far as this segment is still lit
            if (highlightIntensity > 0f) {
                g2.color = highlightColor(highlightIntensity)
                g2.fillRoundRect(0, 0, width, height, SEGMENT_CORNER_RADIUS, SEGMENT_CORNER_RADIUS)
            }

            g2.dispose()
            super.paintComponent(g)
        }
    }

    init {
        // Set window type to POPUP - this allows displaying without stealing focus
        type = Type.POPUP

        // Make window background transparent for rounded corners
        @Suppress("UseJBColor")
        background = java.awt.Color(0, 0, 0, 0)

        // Custom panel with rounded corners
        val panel = object : javax.swing.JPanel(java.awt.BorderLayout()) {
            init {
                isOpaque = false
                border = javax.swing.BorderFactory.createEmptyBorder(5, 12, 5, 12)
            }

            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                )

                // Draw rounded background
                g2.color = normalBackground
                g2.fillRoundRect(0, 0, width, height, TOAST_CORNER_RADIUS, TOAST_CORNER_RADIUS)

                // Draw rounded border
                g2.color = com.intellij.util.ui.JBUI.CurrentTheme.NotificationInfo.borderColor()
                g2.drawRoundRect(0, 0, width - 1, height - 1, TOAST_CORNER_RADIUS, TOAST_CORNER_RADIUS)

                g2.dispose()
                super.paintComponent(g)
            }
        }

        // Create segmented path display using BoxLayout for stable horizontal layout
        val pathPanel = javax.swing.JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            isOpaque = false
        }

        // Add checkmark icon
        val iconLabel = javax.swing.JLabel(com.intellij.icons.AllIcons.General.GreenCheckmark).apply {
            border = javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 6)
        }
        pathPanel.add(iconLabel)

        // Parse path into segments and create labels using the configured separator
        val trimmedSeparator = separator.trim()
        val segments = text.split(trimmedSeparator).map { it.trim() }.filter { it.isNotEmpty() }

        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                // Add separator label (use the configured separator)
                val separatorLabel = javax.swing.JLabel(separator).apply {
                    foreground = com.intellij.util.ui.JBUI.CurrentTheme.NotificationInfo.foregroundColor()
                }
                pathPanel.add(separatorLabel)
            }

            // Add segment label with highlight capability
            val segmentLabel = HighlightableLabel(segment)
            segmentLabels.add(segmentLabel)
            pathPanel.add(segmentLabel)
        }

        panel.add(pathPanel, java.awt.BorderLayout.CENTER)
        contentPane = panel

        // Make content pane transparent for rounded corners to show
        (contentPane as? javax.swing.JComponent)?.isOpaque = false
        pack()

        isAlwaysOnTop = true

        // Dismiss on any mouse click anywhere
        mouseHandler = java.awt.event.AWTEventListener { event ->
            if (event is java.awt.event.MouseEvent && event.id == java.awt.event.MouseEvent.MOUSE_PRESSED) {
                dismissAndCleanup()
            }
        }
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(mouseHandler, java.awt.AWTEvent.MOUSE_EVENT_MASK)

        // Dismiss when another window is activated (e.g., dialog opens)
        windowHandler = java.awt.event.AWTEventListener { event ->
            if (event is java.awt.event.WindowEvent) {
                when (event.id) {
                    java.awt.event.WindowEvent.WINDOW_ACTIVATED,
                    java.awt.event.WindowEvent.WINDOW_OPENED -> {
                        // Another window was activated/opened - dismiss toast
                        if (event.window !== this) {
                            dismissAndCleanup()
                        }
                    }
                }
            }
        }
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(windowHandler, java.awt.AWTEvent.WINDOW_EVENT_MASK)
    }

    /**
     * Sets the target Y position for the rise animation.
     */
    fun setTargetY(y: Int) {
        targetY = y
    }

    /**
     * Shows the toast with a simple fade-in animation (no rise or highlight).
     */
    fun showWithFade() {
        opacity = 0f
        isVisible = true

        val steps = FADE_IN_DURATION_MS / ANIMATION_STEP_MS
        val opacityStep = 1.0f / steps

        animationTimer = Timer(ANIMATION_STEP_MS) {
            opacity = (opacity + opacityStep).coerceAtMost(1.0f)
            if (opacity >= 1.0f) {
                animationTimer?.stop()
                animationTimer = null
            }
        }.apply { start() }
    }

    /**
     * Ease-out cubic: fast at the start, decelerating to a stop.
     *
     * Deliberately has no overshoot. The previous elastic easing crossed the resting position
     * five times and overshot it by 5px, so the text kept moving under the reader's eye for the
     * whole 300ms it took to settle.
     */
    private fun easeOutCubic(t: Float): Float {
        val inverted = 1f - t
        return 1f - inverted * inverted * inverted
    }

    /**
     * Shows the toast rising a short distance into place, then sweeps the highlight.
     */
    fun showWithRise() {
        opacity = 0f
        // Start below target position
        setLocation(x, targetY + RISE_START_OFFSET)
        isVisible = true

        val startTime = System.currentTimeMillis()
        val fadeInDuration = FADE_IN_DURATION_MS.toLong()
        val riseDuration = RISE_DURATION_MS.toLong()

        animationTimer = Timer(ANIMATION_STEP_MS) {
            val elapsed = System.currentTimeMillis() - startTime

            // Opacity animation (linear fade-in)
            val opacityProgress = (elapsed.toFloat() / fadeInDuration).coerceIn(0f, 1f)
            opacity = opacityProgress

            // Position animation (decelerating rise)
            val riseProgress = (elapsed.toFloat() / riseDuration).coerceIn(0f, 1f)
            val currentY = targetY + RISE_START_OFFSET - (RISE_START_OFFSET * easeOutCubic(riseProgress)).toInt()
            setLocation(x, currentY)

            if (elapsed >= riseDuration) {
                animationTimer?.stop()
                animationTimer = null
                setLocation(x, targetY)
                // Sweep only once the text has stopped moving, so it can be read
                startHighlightTrail()
            }
        }.apply { start() }
    }

    /**
     * Sweeps a highlight across the path segments, leaving a fading trail behind it.
     *
     * Every segment is driven from one clock rather than being switched on and off in turn. A
     * segment lights fully the moment the sweep reaches it and then fades over
     * [SWEEP_TRAIL_FADE_MS], which outlasts the step to the next segment, so several segments
     * are lit at once at decreasing strength and the sweep reads as a trail.
     */
    private fun startHighlightTrail() {
        if (segmentLabels.isEmpty()) return

        val steps = segmentLabels.size - 1
        // Keep long paths from still sweeping when the toast begins to fade out
        val segmentDelay = when {
            steps <= 0 -> 0
            else -> minOf(SWEEP_SEGMENT_DELAY_MS, SWEEP_MAX_ADVANCE_MS / steps)
        }
        val totalDuration = steps * segmentDelay + SWEEP_TRAIL_FADE_MS
        val startTime = System.currentTimeMillis()

        highlightTimer = Timer(ANIMATION_STEP_MS) {
            val elapsed = System.currentTimeMillis() - startTime

            segmentLabels.forEachIndexed { index, label ->
                val age = elapsed - index.toLong() * segmentDelay
                label.highlightIntensity = when {
                    age < 0 -> 0f
                    else -> 1f - age.toFloat() / SWEEP_TRAIL_FADE_MS
                }
            }

            if (elapsed >= totalDuration) {
                segmentLabels.forEach { it.highlightIntensity = 0f }
                highlightTimer?.stop()
                highlightTimer = null
            }
        }.apply { start() }
    }

    /**
     * Fades out the toast and disposes it when complete.
     */
    fun fadeOutAndDispose() {
        animationTimer?.stop()
        highlightTimer?.stop()

        // Clear any remaining highlights
        segmentLabels.forEach { it.highlightIntensity = 0f }

        val steps = FADE_OUT_DURATION_MS / ANIMATION_STEP_MS
        val opacityStep = 1.0f / steps

        animationTimer = Timer(ANIMATION_STEP_MS) {
            opacity = (opacity - opacityStep).coerceAtLeast(0f)
            if (opacity <= 0f) {
                animationTimer?.stop()
                animationTimer = null
                cleanupListeners()
                if (currentToast === this@ToastWindow) {
                    currentToast = null
                }
                super.dispose()
            }
        }.apply { start() }
    }

    private fun cleanupListeners() {
        mouseHandler?.let {
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(it)
            mouseHandler = null
        }
        windowHandler?.let {
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(it)
            windowHandler = null
        }
    }

    private fun dismissAndCleanup() {
        // Use fade-out animation for smooth dismissal
        fadeOutAndDispose()
    }

    override fun dispose() {
        animationTimer?.stop()
        animationTimer = null
        highlightTimer?.stop()
        highlightTimer = null
        cleanupListeners()
        super.dispose()
    }
}
