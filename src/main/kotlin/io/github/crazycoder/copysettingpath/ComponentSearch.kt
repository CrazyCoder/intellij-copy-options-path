package io.github.crazycoder.copysettingpath

import com.intellij.ui.SimpleColoredComponent
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JLabel

/**
 * Shared traversal and text-reading primitives for Swing component trees.
 *
 * Every path extractor needs the same two things: walk a component tree, and read the text a
 * component shows. Keeping one implementation of each means a fix reaches all of them.
 */

/**
 * All descendants of a container, depth first, parents before children.
 *
 * Depth-first order matters: callers that take the first match expect the one nearest the top
 * of a branch, not the shallowest across the whole tree.
 *
 * @param container The container to walk.
 * @param maxDepth How many levels to descend. Unlimited by default.
 * @param descendInto Decides whether to walk into a container. Used to skip hidden subtrees.
 */
fun descendants(
    container: Container,
    maxDepth: Int = Int.MAX_VALUE,
    descendInto: (Container) -> Boolean = { true }
): Sequence<Component> = sequence { yieldDescendants(container, maxDepth, descendInto) }

private suspend fun SequenceScope<Component>.yieldDescendants(
    container: Container,
    maxDepth: Int,
    descendInto: (Container) -> Boolean
) {
    if (maxDepth <= 0) return
    for (child in container.components) {
        yield(child)
        if (child is Container && descendInto(child)) {
            yieldDescendants(child, maxDepth - 1, descendInto)
        }
    }
}

/**
 * The component itself followed by all its descendants, depth first.
 *
 * @param maxDepth How many levels to descend. Unlimited by default.
 */
fun Component.selfAndDescendants(maxDepth: Int = Int.MAX_VALUE): Sequence<Component> =
    sequenceOf(this) + ((this as? Container)?.let { descendants(it, maxDepth) } ?: emptySequence())

/**
 * Finds all components of a specific type within a container recursively.
 *
 * @param container The container to search in.
 * @return A sequence of all components of the specified type.
 */
inline fun <reified T : Component> findAllComponentsOfType(container: Container): Sequence<T> =
    descendants(container).filterIsInstance<T>()

/**
 * The text a component displays, with HTML markup removed.
 *
 * Covers the three ways the IntelliJ UI renders text: Swing labels, Swing buttons, and the
 * platform's own [SimpleColoredComponent].
 *
 * @return The text, or null when the component shows none.
 */
fun Component.visibleText(): String? = when (this) {
    is JLabel -> text
    is AbstractButton -> text
    is SimpleColoredComponent -> runCatching { getCharSequence(false).toString() }.getOrNull()
    else -> null
}?.removeHtmlTags()?.takeIf { it.isNotBlank() }
