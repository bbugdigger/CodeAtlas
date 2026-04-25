package com.bugdigger.codeatlas.ui

import com.bugdigger.codeatlas.embedding.HashEmbeddingProvider
import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeAtlasIndexService
import com.bugdigger.codeatlas.index.CodeChunk
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CodeAtlasToolWindowTest : BasePlatformTestCase() {

    fun testInitialStatusReflectsExistingIndexState() {
        val service = project.service<CodeAtlasIndexService>()
        service.embedder = HashEmbeddingProvider(dim = 3)
        service.seedForTests(
            listOf(
                CodeChunk(
                    id = "id:1",
                    qualifiedName = "demo.AuthService.login",
                    kind = ChunkKind.METHOD,
                    signature = "fun login(user: String, pass: String): Boolean",
                    docComment = null,
                    language = "kotlin",
                    virtualFileUrl = "file:///src/AuthService.kt",
                    startOffset = 1,
                    endOffset = 2,
                    containerFqn = "demo.AuthService",
                    contentHash = "hash",
                ),
            ),
            listOf(floatArrayOf(1f, 0f, 0f)),
        )

        val disposable = Disposer.newDisposable()
        try {
            val window = CodeAtlasToolWindow(project, disposable)
            val statusBar = field(window, "statusBar") as IndexStatusBar
            val label = field(statusBar, "label") as JBLabel
            assertEquals("Ready · 1 symbols", label.text)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun field(instance: Any, fieldName: String): Any {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(instance)
    }
}
