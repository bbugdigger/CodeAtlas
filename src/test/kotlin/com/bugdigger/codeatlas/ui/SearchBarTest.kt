package com.bugdigger.codeatlas.ui

import com.intellij.ui.components.JBTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

class SearchBarTest {

    @Test
    fun enterTriggersImmediateSearchWithCurrentText() {
        val calls = CopyOnWriteArrayList<String>()
        val bar = SearchBar("Search", calls::add)
        val field = textFieldOf(bar)

        SwingUtilities.invokeAndWait {
            field.text = "login user"
            field.postActionEvent()
        }

        assertEquals(listOf("login user"), calls)
    }

    @Test
    fun debounceEmitsOnlyLatestTextAfterIdleWindow() {
        val calls = CopyOnWriteArrayList<String>()
        val bar = SearchBar("Search", calls::add)
        val field = textFieldOf(bar)

        SwingUtilities.invokeAndWait {
            field.text = "log"
            field.text = "login"
        }

        waitUntil(timeoutMs = 1500) { calls.size == 1 }
        assertEquals(listOf("login"), calls)
    }

    private fun textFieldOf(bar: SearchBar): JBTextField {
        val field = SearchBar::class.java.getDeclaredField("field")
        field.isAccessible = true
        return field.get(bar) as JBTextField
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("condition did not become true within ${timeoutMs}ms", condition())
    }
}
