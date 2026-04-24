package com.bugdigger.codeatlas.index

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Listens for PSI edits in Kotlin/Java source files and forwards a coalesced
 * batch to [CodeAtlasIndexService.onFileChanged] after a 1-second idle window.
 *
 * The debounce collapses a burst of keystrokes into a single re-index per file.
 * Pending files are held in a [LinkedHashMap] to preserve arrival order and
 * deduplicate repeated edits to the same file.
 */
class CodeAtlasPsiChangeListener(
    private val project: Project,
    private val scope: CoroutineScope,
) : PsiTreeChangeAdapter() {

    private val lock = Any()
    private val pending: LinkedHashMap<String, VirtualFile> = LinkedHashMap()
    private var debounceJob: Job? = null

    override fun childrenChanged(event: PsiTreeChangeEvent) = onChange(event)
    override fun childAdded(event: PsiTreeChangeEvent) = onChange(event)
    override fun childRemoved(event: PsiTreeChangeEvent) = onChange(event)
    override fun childReplaced(event: PsiTreeChangeEvent) = onChange(event)
    override fun propertyChanged(event: PsiTreeChangeEvent) = onChange(event)

    private fun onChange(event: PsiTreeChangeEvent) {
        val vfile = event.file?.virtualFile ?: return
        if (!isKotlinOrJava(vfile)) return
        synchronized(lock) { pending[vfile.url] = vfile }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val files = synchronized(lock) {
            val snapshot = pending.values.toList()
            pending.clear()
            snapshot
        }
        if (files.isEmpty()) return
        val service = project.service<CodeAtlasIndexService>()
        for (vf in files) {
            service.onFileChanged(vf)
        }
    }

    private fun isKotlinOrJava(vf: VirtualFile): Boolean {
        val ext = vf.extension?.lowercase() ?: return false
        return ext == "kt" || ext == "kts" || ext == "java"
    }

    companion object {
        private const val DEBOUNCE_MS = 1000L
    }
}
