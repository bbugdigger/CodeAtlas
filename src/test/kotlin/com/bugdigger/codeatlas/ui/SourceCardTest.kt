package com.bugdigger.codeatlas.ui

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

class SourceCardTest {

    @Test
    fun clickInvokesCallbackWithBackingChunk() {
        val chunk = chunk("demo.AuthService.login", "file:///src/AuthService.kt")
        var clicked: CodeChunk? = null
        val card = SourceCard(index = 1, chunk = chunk, onClick = { clicked = it })

        SwingUtilities.invokeAndWait {
            val event = MouseEvent(card, MouseEvent.MOUSE_CLICKED, 0L, 0, 5, 5, 1, false)
            for (l in card.mouseListeners) l.mouseClicked(event)
        }

        assertSame(chunk, clicked)
    }

    @Test
    fun rendersIndexBadge() {
        val chunk = chunk("demo.Foo.bar", "file:///src/Foo.kt")
        val card = SourceCard(index = 3, chunk = chunk) {}
        val labels = mutableListOf<String>()
        collectLabelText(card, labels)
        assertEquals("expected [3] badge among labels $labels", true, labels.contains("[3]"))
    }

    private fun collectLabelText(c: java.awt.Container, out: MutableList<String>) {
        for (child in c.components) {
            if (child is com.intellij.ui.components.JBLabel) out += child.text.orEmpty()
            if (child is java.awt.Container) collectLabelText(child, out)
        }
    }

    private fun chunk(fqn: String, url: String) = CodeChunk(
        id = "id:$fqn",
        qualifiedName = fqn,
        kind = ChunkKind.METHOD,
        signature = "fun $fqn()",
        docComment = null,
        language = "kotlin",
        virtualFileUrl = url,
        startOffset = 0,
        endOffset = 10,
        containerFqn = fqn.substringBeforeLast('.'),
        contentHash = "hash",
    )
}
