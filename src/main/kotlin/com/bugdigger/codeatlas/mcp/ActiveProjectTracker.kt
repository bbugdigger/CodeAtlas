package com.bugdigger.codeatlas.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.wm.IdeFocusManager
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Application-scoped singleton that tracks which open project should receive MCP tool calls.
 *
 * The MCP server is one-per-IDE-instance (see [McpServerService]) but each tool call has to
 * resolve to a single [Project] because the index lives at project scope. Strategy:
 *
 *  1. On project open, push it to the front of an LRU.
 *  2. On project close, drop it from the LRU.
 *  3. Tools call [currentProject] which returns the most-recently-active still-open project,
 *     or `null` if none are open.
 *
 * We also peek at [IdeFocusManager] when resolving so window focus changes inside an already-open
 * IDE bias the result toward the visible project, even if the user hasn't opened anything new.
 */
@Service(Service.Level.APP)
class ActiveProjectTracker {

    // Newest first. Holds weak refs so a stale entry can never keep a Project alive past close.
    private val lru: MutableList<WeakReference<Project>> = CopyOnWriteArrayList()

    init {
        val app = ApplicationManager.getApplication()
        val connection = app.messageBus.connect(app)
        connection.subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
            // projectOpened is deprecated in favor of `ProjectActivity` for project-scoped work,
            // but we want a single application-scoped listener that fires for *every* project as
            // it opens. The deprecated overload is still the single-application hook the platform
            // provides for that use case, so we suppress the warning here.
            @Suppress("OVERRIDE_DEPRECATION")
            override fun projectOpened(project: Project) {
                touch(project)
            }
            // We don't override projectClosed (deprecated). The lazy isDisposed check + WeakRef
            // GC in currentProject() and touch() is sufficient — closed projects are skipped
            // and their LRU entries get GC'd along with the Project itself.
        })
        // Seed with anything already open at service-construction time.
        ProjectManager.getInstance().openProjects.forEach(::touch)
    }

    /**
     * Returns the most-recently-active still-open project, or `null` if no project is open.
     *
     * Tools are expected to call this on every invocation; project references are intentionally
     * not cached across calls.
     */
    fun currentProject(): Project? {
        // Prefer the focused project when one exists and is still open — handles window switches
        // between two long-lived projects without waiting for an open/close event.
        val focused = runCatching {
            IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
        }.getOrNull()
        if (focused != null && !focused.isDisposed) {
            touch(focused)
            return focused
        }
        // Fall back to the LRU head, scrubbing dead refs as we go.
        val it = lru.iterator()
        while (it.hasNext()) {
            val p = it.next().get()
            if (p != null && !p.isDisposed) return p
        }
        // Last resort: ProjectManager itself (avoids cold-start nulls before any focus event arrives).
        return ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
    }

    private fun touch(project: Project) {
        if (project.isDisposed) return
        lru.removeAll { it.get() == null || it.get() == project }
        lru.add(0, WeakReference(project))
    }

    /** Test seam: clear the LRU so unit tests start from a known state. */
    internal fun clearForTests() {
        lru.clear()
    }

    companion object {
        fun getInstance(): ActiveProjectTracker =
            ApplicationManager.getApplication().getService(ActiveProjectTracker::class.java)
    }
}
