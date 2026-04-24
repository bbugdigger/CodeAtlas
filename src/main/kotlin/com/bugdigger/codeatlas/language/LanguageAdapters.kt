package com.bugdigger.codeatlas.language

import com.intellij.psi.PsiFile

/**
 * Static registry of [LanguageAdapter] instances, queried in registration order.
 *
 * A Phase-2 expansion (Python, TS, Go) adds entries here; nothing else changes.
 */
object LanguageAdapters {
    private val adapters: List<LanguageAdapter> = listOf(
        KotlinLanguageAdapter(),
        JavaLanguageAdapter(),
    )

    fun find(file: PsiFile): LanguageAdapter? = adapters.firstOrNull { it.supports(file) }
}
