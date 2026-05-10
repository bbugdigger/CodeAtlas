package com.bugdigger.codeatlas.searcheverywhere

import com.bugdigger.codeatlas.embedding.HashEmbeddingProvider
import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeAtlasIndexService
import com.bugdigger.codeatlas.index.CodeChunk
import com.bugdigger.codeatlas.search.RankedResult
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Processor

class CodeAtlasSearchContributorTest : BasePlatformTestCase() {

    private val testDim = 8

    override fun setUp() {
        super.setUp()
        // Reset the index to a known empty state with a deterministic, light-weight embedder.
        // Setting embedder clears chunks/vectors by design (CodeAtlasIndexService contract).
        project.service<CodeAtlasIndexService>().embedder = HashEmbeddingProvider(dim = testDim)
    }

    fun testFetchElementsReturnsResultsInScoreOrder() {
        val service = project.service<CodeAtlasIndexService>()
        service.seedForTests(
            listOf(
                chunk("alpha"),
                chunk("beta"),
                chunk("gamma"),
            ),
            listOf(
                FloatArray(testDim) { 1f },
                FloatArray(testDim) { 1f },
                FloatArray(testDim) { 1f },
            ),
        )

        val contributor = CodeAtlasSearchContributor(syntheticEvent(), project)
        val collected = mutableListOf<RankedResult>()
        val consumer = Processor<RankedResult> { r ->
            collected.add(r)
            true
        }

        contributor.fetchElements("alpha", EmptyProgressIndicator(), consumer)

        assertFalse(
            "Expected at least one result for the seeded query, got none",
            collected.isEmpty(),
        )
        // Priorities (used by the platform to order results) must be monotonically
        // non-increasing in the order they were emitted.
        val priorities = collected.map { contributor.getElementPriority(it, "alpha") }
        for (i in 1 until priorities.size) {
            assertTrue(
                "Priorities out of order at index $i: ${priorities[i - 1]} < ${priorities[i]}",
                priorities[i - 1] >= priorities[i],
            )
        }
    }

    fun testEmptyPatternProducesNoResults() {
        val service = project.service<CodeAtlasIndexService>()
        service.seedForTests(
            listOf(chunk("anything")),
            listOf(FloatArray(testDim) { 1f }),
        )

        val contributor = CodeAtlasSearchContributor(syntheticEvent(), project)
        var called = false
        contributor.fetchElements(
            pattern = "",
            progressIndicator = EmptyProgressIndicator(),
            consumer = Processor { called = true; true },
        )

        assertFalse("Empty pattern should not invoke the consumer", called)
    }

    fun testAdvertisementShownWhenIndexEmpty() {
        // Don't seed anything — service.chunkCount will be 0.
        val contributor = CodeAtlasSearchContributor(syntheticEvent(), project)

        val advertisement = contributor.advertisement

        assertNotNull("Empty index should advertise progress hint", advertisement)
        assertTrue(
            "Advertisement should mention building",
            advertisement!!.contains("building", ignoreCase = true),
        )
    }

    fun testAdvertisementSuppressedWhenIndexReady() {
        val service = project.service<CodeAtlasIndexService>()
        service.seedForTests(
            listOf(chunk("ready")),
            listOf(FloatArray(testDim) { 1f }),
        )
        val contributor = CodeAtlasSearchContributor(syntheticEvent(), project)

        assertNull(
            "Ready index should not show an advertisement",
            contributor.advertisement,
        )
    }

    private fun syntheticEvent(): AnActionEvent {
        val context = SimpleDataContext.getProjectContext(project)
        return AnActionEvent.createFromDataContext("test", Presentation(), context)
    }

    private fun chunk(name: String): CodeChunk = CodeChunk(
        id = "test-$name",
        qualifiedName = name,
        kind = ChunkKind.FUNCTION,
        signature = "fun $name()",
        docComment = null,
        language = "kotlin",
        virtualFileUrl = "file:///$name.kt",
        startOffset = 0,
        endOffset = 0,
        containerFqn = null,
        contentHash = "hash-$name",
    )
}
