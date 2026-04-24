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
 * their embedding vectors. Supports both full rebuilds and single-file
 * extraction for incremental re-indexing on PSI changes.
 */
class IndexBuilder(
    private val project: Project,
    private val embedder: EmbeddingProvider,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {

    /** Full rebuild over all source files. Invokes [onProgress] once per file processed. */
    suspend fun build(
        indicator: ProgressIndicator,
        includeTests: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BuildResult {
        val files = collectSourceFiles(includeTests)
        if (files.isEmpty()) return BuildResult(emptyList(), emptyList())

        indicator.isIndeterminate = false
        indicator.text = "CodeAtlas — scanning sources"
        val psiManager = PsiManager.getInstance(project)
        val chunks = mutableListOf<CodeChunk>()
        val total = files.size
        for ((i, vf) in files.withIndex()) {
            indicator.checkCanceled()
            indicator.fraction = 0.5 * i / total
            indicator.text2 = vf.presentableName
            onProgress(i, total)
            ReadAction.run<RuntimeException> {
                val psi = psiManager.findFile(vf) ?: return@run
                val adapter = LanguageAdapters.find(psi) ?: return@run
                chunks += adapter.extract(psi)
            }
        }

        val vectors = embedInBatches(chunks, indicator)
        onProgress(total, total)
        return BuildResult(chunks, vectors)
    }

    /** Re-extract and re-embed chunks for a single file (used by incremental updates). */
    suspend fun extractAndEmbedForFile(vfile: VirtualFile): BuildResult {
        val chunks = ReadAction.compute<List<CodeChunk>, RuntimeException> {
            val psi = PsiManager.getInstance(project).findFile(vfile) ?: return@compute emptyList()
            val adapter = LanguageAdapters.find(psi) ?: return@compute emptyList()
            adapter.extract(psi)
        }
        if (chunks.isEmpty()) return BuildResult(emptyList(), emptyList())
        val vectors = embedder.embed(chunks.map { it.embeddingInput() })
        return BuildResult(chunks, vectors)
    }

    private fun collectSourceFiles(includeTests: Boolean): List<VirtualFile> {
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
        return files
    }

    private suspend fun embedInBatches(
        chunks: List<CodeChunk>,
        indicator: ProgressIndicator,
    ): List<FloatArray> {
        if (chunks.isEmpty()) return emptyList()
        indicator.text = "CodeAtlas — embedding ${chunks.size} symbols"
        val vectors = ArrayList<FloatArray>(chunks.size)
        for (start in chunks.indices step batchSize) {
            indicator.checkCanceled()
            val end = (start + batchSize).coerceAtMost(chunks.size)
            val batch = chunks.subList(start, end)
            vectors += embedder.embed(batch.map { it.embeddingInput() })
            indicator.fraction = 0.5 + 0.5 * end / chunks.size
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
