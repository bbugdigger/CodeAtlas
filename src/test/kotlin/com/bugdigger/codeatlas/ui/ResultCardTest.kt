package com.bugdigger.codeatlas.ui

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import com.bugdigger.codeatlas.search.RankedResult
import com.intellij.ui.components.JBLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultCardTest {

    @Test
    fun bindPopulatesLabelsFromRankedResult() {
        val card = ResultCard()
        val result = RankedResult(
            chunk = CodeChunk(
                id = "id:1",
                qualifiedName = "demo.AuthService.login",
                kind = ChunkKind.METHOD,
                signature = "fun login(user: String, pass: String): Boolean",
                docComment = null,
                language = "kotlin",
                virtualFileUrl = "file:///src/AuthService.kt",
                startOffset = 10,
                endOffset = 20,
                containerFqn = "demo.AuthService",
                contentHash = "hash",
            ),
            finalScore = 0.8f,
            vectorScore = 0.7f,
        )

        card.bind(result)

        assertEquals("demo.AuthService.login", label(card, "nameLabel").text)
        assertEquals("fun login(user: String, pass: String): Boolean", label(card, "sigLabel").text)
        assertEquals("AuthService.kt", label(card, "pathLabel").text)
        assertTrue("icon should be set", label(card, "iconLabel").icon != null)
    }

    private fun label(card: ResultCard, fieldName: String): JBLabel {
        val field = ResultCard::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(card) as JBLabel
    }
}
