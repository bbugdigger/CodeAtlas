package com.bugdigger.codeatlas.index

import com.bugdigger.codeatlas.language.LanguageAdapters
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

/**
 * Walks project sources and extracts [CodeChunk]s via the matching [com.bugdigger.codeatlas.language.LanguageAdapter].
 *
 * Phase 1 Week 1 scaffold: no embedding, no persistence. Returns the full chunk list once the walk
 * finishes. Week 2 will pipe chunks through the embedding provider and vector store.
 */
class IndexBuilder(private val project: Project) {

    /** Walks content roots and returns extracted chunks. Runs entirely under read actions. */
    fun buildAllChunks(indicator: ProgressIndicator, includeTests: Boolean = false): List<CodeChunk> {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val files = mutableListOf<VirtualFile>()
        ReadAction.run<RuntimeException> {
            fileIndex.iterateContent { vf ->
                if (!vf.isDirectory && isKotlinOrJava(vf)
                    && (includeTests || !fileIndex.isInTestSourceContent(vf))
                    && fileIndex.isInSourceContent(vf)
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
            indicator.fraction = i.toDouble() / total
            indicator.text2 = vf.presentableName
            ReadAction.run<RuntimeException> {
                val psi = psiManager.findFile(vf) ?: return@run
                val adapter = LanguageAdapters.find(psi) ?: return@run
                out += adapter.extract(psi)
            }
        }
        indicator.fraction = 1.0
        return out
    }

    private fun isKotlinOrJava(vf: VirtualFile): Boolean {
        val ext = vf.extension?.lowercase() ?: return false
        return ext == "kt" || ext == "kts" || ext == "java"
    }
}
