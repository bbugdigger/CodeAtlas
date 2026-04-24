package com.bugdigger.codeatlas.startup

import com.bugdigger.codeatlas.index.CodeAtlasIndexService
import com.bugdigger.codeatlas.index.CodeAtlasPsiChangeListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Runs after the project opens: kicks off a background full index build and
 * registers the incremental [CodeAtlasPsiChangeListener]. Both operations are
 * bound to the project lifecycle — the listener and the coroutine scope are
 * disposed when the project closes.
 */
class CodeAtlasStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val service = project.service<CodeAtlasIndexService>()
        service.requestFullIndex()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeDisposable = Disposable { scope.cancel() }
        Disposer.register(project, scopeDisposable)

        val listener = CodeAtlasPsiChangeListener(project, scope)
        PsiManager.getInstance(project).addPsiTreeChangeListener(listener, scopeDisposable)
    }
}
