package com.bugdigger.codeatlas.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Boots the application-scoped MCP server on the first project open after IDE start.
 *
 * The server lives at application scope (one per IDE instance) but ProjectActivity is the
 * cleanest hook the platform exposes for "the IDE has finished starting and at least one
 * project is open". After the first invocation, [McpServerService.ensureStarted] is idempotent,
 * so subsequent project opens are cheap no-ops.
 *
 * Also touches [ActiveProjectTracker] so the tracker's `init` block runs and starts subscribing
 * to project open/close events even before any tool call resolves it.
 */
class McpServerLifecycle : ProjectActivity {

    private val log = Logger.getInstance(McpServerLifecycle::class.java)

    override suspend fun execute(project: Project) {
        // Touch the tracker so its singleton init runs and message-bus subscription starts.
        ActiveProjectTracker.getInstance()
        // Idempotent — the second project open is a no-op.
        runCatching { McpServerService.getInstance().ensureStarted() }
            .onFailure { log.warn("CodeAtlas MCP server failed to start", it) }
    }
}
