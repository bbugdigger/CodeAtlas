package com.bugdigger.codeatlas.search

import com.bugdigger.codeatlas.index.CodeChunk
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * Re-rank signal driven by the IDE's stub index.
 *
 * For each identifier-shaped token in the query, looks up classes and methods by
 * short name through [PsiShortNamesCache], collects their fully-qualified names,
 * and emits a boost for every candidate [CodeChunk] whose [CodeChunk.qualifiedName]
 * matches one of those names.
 *
 * `PsiShortNamesCache` is backed by IntelliJ's `StubIndex` and covers Kotlin
 * declarations through the platform's light-class layer, so a single API is
 * enough to serve both languages this plugin supports today.
 */
class StubIndexSignal(private val project: Project) {

    /** Returns a per-chunk-id boost in `[0, 1]`. Empty if no token matched the index. */
    fun computeBoosts(query: String, candidates: List<CodeChunk>): Map<String, Float> {
        if (candidates.isEmpty()) return emptyMap()
        val tokens = identifierTokens(query)
        if (tokens.isEmpty()) return emptyMap()

        val matchedFqns = ReadAction.compute<Set<String>, RuntimeException> {
            val out = mutableSetOf<String>()
            val cache = PsiShortNamesCache.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)
            for (token in tokens) {
                for (cls in cache.getClassesByName(token, scope)) {
                    cls.qualifiedName?.let(out::add)
                }
                for (method in cache.getMethodsByName(token, scope)) {
                    val owner = method.containingClass?.qualifiedName ?: continue
                    out += "$owner.${method.name}"
                }
            }
            out
        }
        if (matchedFqns.isEmpty()) return emptyMap()

        return candidates
            .filter { it.qualifiedName in matchedFqns }
            .associate { it.id to 1.0f }
    }

    private fun identifierTokens(query: String): List<String> =
        query.split(NON_IDENT).filter { it.length >= MIN_TOKEN_LEN }.distinct()

    companion object {
        private val NON_IDENT = Regex("[^A-Za-z0-9_]+")
        private const val MIN_TOKEN_LEN = 3
    }
}
