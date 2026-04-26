package com.bugdigger.codeatlas.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persistent settings for the embedded MCP server.
 *
 * The server itself is one-per-IDE-instance (see [McpServerService]) so its configuration
 * lives at application scope rather than per-project. Per-project state — what to index,
 * which LLM provider to use — stays in [com.bugdigger.codeatlas.settings.CodeAtlasSettingsService].
 *
 * No secrets here; the MCP server binds to 127.0.0.1 only.
 */
@State(name = "CodeAtlasMcp", storages = [Storage("codeAtlas-mcp.xml")])
@Service(Service.Level.APP)
class McpServerSettings :
    SerializablePersistentStateComponent<McpServerSettings.SettingsState>(SettingsState()) {

    data class SettingsState(
        val enabled: Boolean = true,
        val port: Int = DEFAULT_PORT,
    )

    var enabled: Boolean
        get() = state.enabled
        set(value) {
            updateState { it.copy(enabled = value) }
        }

    var port: Int
        get() = state.port.takeIf { it in MIN_PORT..MAX_PORT } ?: DEFAULT_PORT
        set(value) {
            require(value in MIN_PORT..MAX_PORT) { "MCP port must be in $MIN_PORT..$MAX_PORT" }
            updateState { it.copy(port = value) }
        }

    companion object {
        // Sits one below the JetBrains MCP plugin's 64342+ range to minimize collision risk.
        const val DEFAULT_PORT = 64340
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        fun getInstance(): McpServerSettings =
            ApplicationManager.getApplication().getService(McpServerSettings::class.java)
    }
}
