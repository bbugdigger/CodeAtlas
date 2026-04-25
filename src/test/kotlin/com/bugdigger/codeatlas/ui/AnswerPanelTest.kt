package com.bugdigger.codeatlas.ui

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import com.bugdigger.codeatlas.rag.AnswerToken
import com.bugdigger.codeatlas.rag.FakeAnswerGenerator
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JTextPane
import javax.swing.SwingUtilities

class AnswerPanelTest {

    @Test
    fun streamedDeltasAndDoneRenderProseAndSources() {
        val chunks = listOf(
            chunk("demo.Foo.a", "file:///src/Foo.kt"),
            chunk("demo.Foo.b", "file:///src/Foo.kt"),
        )
        val generator = FakeAnswerGenerator(
            scriptedDeltas = listOf("See ", "[1] and ", "[2]."),
        )

        val panel = AnswerPanel(onCitationClick = {})
        val tokens = runBlocking { generator.generate("q", chunks).toList() }

        SwingUtilities.invokeAndWait {
            for (t in tokens) panel.consume(t)
        }
        flushEdt()

        val text = textPaneOf(panel).text
        assertTrue("prose should contain citation markers, was: $text", text.contains("[1]") && text.contains("[2]"))
        assertEquals(2, sourcesPanelChildCount(panel))
    }

    @Test
    fun citationClickInvokesCallbackWithMatchingChunk() {
        val chunks = listOf(chunk("demo.Only.one", "file:///src/Only.kt"))
        var clicked: CodeChunk? = null
        val panel = AnswerPanel(onCitationClick = { clicked = it })

        SwingUtilities.invokeAndWait {
            panel.consume(AnswerToken.Delta("Ref [1] here"))
            panel.consume(AnswerToken.Done(chunks))
        }
        flushEdt()

        // Click simulated via hyperlink-equivalent: locate the citation attribute position
        // in the document and dispatch a MouseEvent at that character offset.
        val pane = textPaneOf(panel)
        pane.setSize(400, 200)
        pane.doLayout()
        val doc = pane.styledDocument
        val citationPos = (0 until doc.length).firstOrNull { pos ->
            val el = doc.getCharacterElement(pos)
            el.attributes.getAttribute("codeatlas.citation") != null
        } ?: error("no citation attribute found in ${pane.text}")

        SwingUtilities.invokeAndWait {
            val rect = pane.modelToView2D(citationPos) ?: error("no view for pos $citationPos")
            val x = rect.x.toInt() + 2
            val y = rect.y.toInt() + 2
            val e = java.awt.event.MouseEvent(pane, java.awt.event.MouseEvent.MOUSE_CLICKED, 0L, 0, x, y, 1, false)
            for (l in pane.mouseListeners) l.mouseClicked(e)
        }
        flushEdt()

        assertSame(chunks[0], clicked)
    }

    @Test
    fun errorTokenShowsStatusMessage() {
        val panel = AnswerPanel(onCitationClick = {})
        SwingUtilities.invokeAndWait {
            panel.consume(AnswerToken.Delta("partial "))
            panel.consume(AnswerToken.Error("boom"))
        }
        flushEdt()

        val pane = textPaneOf(panel)
        assertTrue("prose kept on error, was: ${pane.text}", pane.text.contains("partial"))
        assertEquals("boom", statusOf(panel))
    }

    private fun flushEdt() = SwingUtilities.invokeAndWait { /* drain */ }

    private fun textPaneOf(panel: AnswerPanel): JTextPane {
        val f = AnswerPanel::class.java.getDeclaredField("textPane")
        f.isAccessible = true
        return f.get(panel) as JTextPane
    }

    private fun sourcesPanelChildCount(panel: AnswerPanel): Int {
        val f = AnswerPanel::class.java.getDeclaredField("sourcesPanel")
        f.isAccessible = true
        return (f.get(panel) as javax.swing.JPanel).componentCount
    }

    private fun statusOf(panel: AnswerPanel): String {
        val f = AnswerPanel::class.java.getDeclaredField("statusLabel")
        f.isAccessible = true
        return (f.get(panel) as com.intellij.ui.components.JBLabel).text.orEmpty()
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
