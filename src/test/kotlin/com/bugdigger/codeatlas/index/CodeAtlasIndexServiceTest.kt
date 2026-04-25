package com.bugdigger.codeatlas.index

import com.bugdigger.codeatlas.embedding.EmbeddingProvider
import com.bugdigger.codeatlas.embedding.HashEmbeddingProvider
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotEquals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for [CodeAtlasIndexService] safety invariants.
 *
 * Covers:
 *  - **B1** single-flight gate on [CodeAtlasIndexService.requestFullIndex];
 *  - **B7** per-modelId cache filenames so embedder swaps don't clobber
 *    the previous provider's cached vectors.
 */
class CodeAtlasIndexServiceTest : BasePlatformTestCase() {

    fun testCacheFilePathChangesWithModelId() {
        val service = project.service<CodeAtlasIndexService>()

        val pathSmall = service.cacheFilePath(HashEmbeddingProvider(dim = 4))
        val pathLarge = service.cacheFilePath(HashEmbeddingProvider(dim = 8))

        // Different modelId -> different filename (the dim is part of HashEmbeddingProvider.modelId).
        assertNotEquals(pathSmall, pathLarge)

        // But both belong to the same project key, so they share a parent directory.
        assertEquals(pathSmall.parent, pathLarge.parent)

        // And both end in the conventional .bin extension.
        assertTrue(pathSmall.fileName.toString().endsWith(".bin"))
        assertTrue(pathLarge.fileName.toString().endsWith(".bin"))
    }

    fun testCacheFilePathStableForSameModelId() {
        val service = project.service<CodeAtlasIndexService>()
        val a = service.cacheFilePath(HashEmbeddingProvider(dim = 4))
        val b = service.cacheFilePath(HashEmbeddingProvider(dim = 4))
        assertEquals(a, b)
    }

    fun testRequestFullIndexIsSingleFlight() {
        val callCount = AtomicInteger(0)
        val embedderEntered = CountDownLatch(1)
        val embedderRelease = CountDownLatch(1)

        val service = project.service<CodeAtlasIndexService>()
        service.embedder = object : EmbeddingProvider {
            override val dim: Int = 4
            override val modelId: String = "test-blocking-${System.nanoTime()}"
            override suspend fun embed(texts: List<String>): List<FloatArray> {
                callCount.incrementAndGet()
                embedderEntered.countDown()
                // Hold the build "in flight" until the test releases it.
                embedderRelease.await()
                return texts.map { FloatArray(dim) }
            }
        }
        // Need at least one source file so the build actually walks down to embed().
        myFixture.configureByText("Foo.kt", "class Foo { fun bar() = 1 }")

        // Run the first build on a background thread so the test thread can issue
        // the second call while the first is still inside embed().
        val firstBuildThread = Thread { service.requestFullIndex() }
        firstBuildThread.isDaemon = true
        firstBuildThread.start()
        try {
            assertTrue(
                "expected first build to enter the embedder within 10 s",
                embedderEntered.await(10, TimeUnit.SECONDS),
            )

            // Second call: should be a no-op while the first is in flight.
            service.requestFullIndex()
        } finally {
            embedderRelease.countDown()
            firstBuildThread.join(TimeUnit.SECONDS.toMillis(10))
        }

        assertEquals(
            "single-flight gate should have prevented the second build from running",
            1,
            callCount.get(),
        )
    }
}
