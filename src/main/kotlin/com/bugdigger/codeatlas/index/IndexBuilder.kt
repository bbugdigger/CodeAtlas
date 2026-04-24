package com.bugdigger.codeatlas.index

import com.bugdigger.codeatlas.embedding.EmbeddingProvider
import com.bugdigger.codeatlas.language.LanguageAdapters
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

/**
 * Walks project sources and produces a [BuildResult] of chunks paired with
 * their embedding vectors.
 *
 * Steps:
 * 1. Under a read action, enumerate Kotlin/Java source files via [ProjectFileIndex].
 * 2. For each file, run the matching [com.bugdigger.codeatlas.language.LanguageAdapter]
 *    to produce [CodeChunk]s.
 * 3. Embed chunks in batches of [batchSize].
 */
class IndexBuilder(
    private val project: Project,
    private val embedder: EmbeddingProvider,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {

    suspend fun build(indicator: ProgressIndicator, includeTests: Boolean = false): BuildResult {
        val chunks = extractChunks(indicator, includeTests)
        if (chunks.isEmpty()) return BuildResult(emptyList(), emptyList())
        val vectors = embedChunks(chunks, indicator)
        return BuildResult(chunks, vectors)
    }

    private fun extractChunks(indicator: ProgressIndicator, includeTests: Boolean): List<CodeChunk> {
        indicator.text = "CodeAtlas — scanning sources"
        val fileIndex = ProjectFileIndex.getInstance(project)
        val files = mutableListOf<VirtualFile>()
        ReadAction.run<RuntimeException> {
            fileIndex.iterateContent { vf ->
                if (!vf.isDirectory
                    && isKotlinOrJava(vf)
                    && fileIndex.isInSourceContent(vf)
                    && (includeTests || !fileIndex.isInTestSourceContent(vf))
                ) {
                    files += vf
                }
                true
            }
        }
        indicator.isIndeterminate = false
        val psiManager = PsiManager.getInstance(project)
        val out = mutableListOf<CodeChunk>()
        val total = files.size.coerceAtLeast(1)
        for ((i, vf) in files.withIndex()) {
            indicator.checkCanceled()
            indicator.fraction = 0.5 * i / total
            indicator.text2 = vf.presentableName
            ReadAction.run<RuntimeException> {
                val psi = psiManager.findFile(vf) ?: return@run
                val adapter = LanguageAdapters.find(psi) ?: return@run
                out += adapter.extract(psi)
            }
        }
        return out
    }

    private suspend fun embedChunks(
        chunks: List<CodeChunk>,
        indicator: ProgressIndicator,
    ): List<FloatArray> {
        indicator.text = "CodeAtlas — embedding ${chunks.size} symbols"
        val vectors = ArrayList<FloatArray>(chunks.size)
        var processed = 0
        for (start in chunks.indices step batchSize) {
            indicator.checkCanceled()
            val end = (start + batchSize).coerceAtMost(chunks.size)
            val batch = chunks.subList(start, end)
            vectors += embedder.embed(batch.map { it.embeddingInput() })
            processed = end
            indicator.fraction = 0.5 + 0.5 * processed / chunks.size.coerceAtLeast(1)
        }
        indicator.fraction = 1.0
        return vectors
    }

    private fun isKotlinOrJava(vf: VirtualFile): Boolean {
        val ext = vf.extension?.lowercase() ?: return false
        return ext == "kt" || ext == "kts" || ext == "java"
    }

    data class BuildResult(
        val chunks: List<CodeChunk>,
        val vectors: List<FloatArray>,
    )

    companion object {
        private const val DEFAULT_BATCH_SIZE = 16
    }
}
