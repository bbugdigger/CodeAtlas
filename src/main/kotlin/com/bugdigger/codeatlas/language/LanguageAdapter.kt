package com.bugdigger.codeatlas.language

import com.bugdigger.codeatlas.index.CodeChunk
import com.intellij.psi.PsiFile

/**
 * Language-specific extractor that turns a PSI file into retrieval chunks.
 *
 * Implementations must be stateless and safe to call under a read action.
 */
interface LanguageAdapter {
    /** Language identifier used in [CodeChunk.language], e.g. "kotlin", "java". */
    val languageId: String

    fun supports(file: PsiFile): Boolean

    fun extract(file: PsiFile): List<CodeChunk>
}
