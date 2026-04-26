package com.bugdigger.codeatlas.mcp

import com.bugdigger.codeatlas.mcp.tools.AskCodebaseTool
import com.bugdigger.codeatlas.mcp.tools.SearchCodeTool
import com.bugdigger.codeatlas.mcp.tools.ToolResult
import com.bugdigger.codeatlas.mcp.tools.askBackendFor
import com.bugdigger.codeatlas.mcp.tools.searchBackendFor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Application-scoped owner of the embedded MCP server.
 *
 * One server per IDE instance. Binds Ktor + CIO to `127.0.0.1:<port>` and exposes two MCP tools:
 *  - [SearchCodeTool.NAME] — semantic search over the active project's CodeAtlas index.
 *  - [AskCodebaseTool.NAME] — retrieval-augmented answers using the configured LLM provider.
 *
 * Lifecycle:
 *  - [ensureStarted] is called from [McpServerLifecycle] on the first project open.
 *  - [restart] re-reads settings and rebinds (used by the settings UI when the user toggles
 *    enable or changes port).
 *  - [dispose] runs on IDE shutdown via the application service contract — it stops the engine
 *    so the port releases cleanly.
 *
 * Thread-safety: [lock] guards the `engine`/`status` pair. Calls into the Ktor `start`/`stop`
 * functions block the calling thread; both [ensureStarted] and [restart] are expected to be
 * called from background threads (the lifecycle activity and settings apply path both qualify).
 */
@Service(Service.Level.APP)
class McpServerService : Disposable {

    private val log = Logger.getInstance(McpServerService::class.java)
    private val lock = Any()
    private var engine: EmbeddedServer<*, *>? = null

    @Volatile
    private var status: Status = Status.Stopped

    sealed class Status {
        data object Stopped : Status()
        data class Listening(val port: Int) : Status()
        data class Failed(val port: Int, val reason: String) : Status()
    }

    /** Latest known server status. Read by the settings UI to render the status label. */
    val currentStatus: Status
        get() = status

    /** URL hosts should connect to when [Status.Listening]. */
    val currentUrl: String?
        get() = (status as? Status.Listening)?.let { url(it.port) }

    /**
     * Idempotent: starts the server if enabled and not already listening on the configured port,
     * stops it if disabled. Safe to call repeatedly.
     */
    fun ensureStarted() {
        val s = McpServerSettings.getInstance()
        synchronized(lock) {
            if (!s.enabled) {
                stopInternalLocked()
                return
            }
            val current = status
            if (current is Status.Listening && current.port == s.port) return
            stopInternalLocked()
            startInternalLocked(s.port)
        }
    }

    /** Stop and re-start, picking up any settings changes. */
    fun restart() {
        synchronized(lock) {
            stopInternalLocked()
        }
        ensureStarted()
    }

    private fun startInternalLocked(port: Int) {
        try {
            val mcpServer = buildMcpServer()
            val ktor = embeddedServer(CIO, host = HOST, port = port) {
                mcpStreamableHttp(path = MCP_PATH) { mcpServer }
            }
            ktor.start(wait = false)
            engine = ktor
            status = Status.Listening(port)
            log.info("CodeAtlas MCP server listening on ${url(port)}")
        } catch (t: Throwable) {
            log.warn("CodeAtlas MCP server failed to bind on port $port: ${t.message}", t)
            status = Status.Failed(port, t.message ?: t::class.simpleName ?: "unknown error")
            engine = null
        }
    }

    private fun stopInternalLocked() {
        val toStop = engine
        engine = null
        status = Status.Stopped
        if (toStop != null) {
            runCatching { toStop.stop(gracePeriodMillis = 500L, timeoutMillis = 2_000L) }
                .onFailure { log.warn("Error stopping MCP server", it) }
        }
    }

    private fun buildMcpServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
            ),
        )
        registerSearchCodeTool(server)
        registerAskCodebaseTool(server)
        return server
    }

    private fun registerSearchCodeTool(server: Server) {
        val tool = SearchCodeTool {
            ActiveProjectTracker.getInstance().currentProject()?.let(::searchBackendFor)
        }
        server.addTool(
            name = SearchCodeTool.NAME,
            description = SearchCodeTool.DESCRIPTION,
            inputSchema = searchCodeSchema(),
        ) { request ->
            val args = request.arguments ?: buildJsonObject { }
            tool.handle(args).toCallToolResult()
        }
    }

    private fun registerAskCodebaseTool(server: Server) {
        val tool = AskCodebaseTool {
            ActiveProjectTracker.getInstance().currentProject()?.let(::askBackendFor)
        }
        server.addTool(
            name = AskCodebaseTool.NAME,
            description = AskCodebaseTool.DESCRIPTION,
            inputSchema = askCodebaseSchema(),
        ) { request ->
            val args = request.arguments ?: buildJsonObject { }
            tool.handle(args).toCallToolResult()
        }
    }

    override fun dispose() {
        synchronized(lock) {
            stopInternalLocked()
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val MCP_PATH = "/mcp"
        const val SERVER_NAME = "codeatlas-mcp"
        const val SERVER_VERSION = "1.0.0"

        fun getInstance(): McpServerService =
            ApplicationManager.getApplication().getService(McpServerService::class.java)

        fun url(port: Int): String = "http://$HOST:$port$MCP_PATH"
    }
}

private fun ToolResult.toCallToolResult(): CallToolResult = when (this) {
    is ToolResult.Success -> CallToolResult(
        content = textBlocks.map { TextContent(text = it) },
    )
    is ToolResult.Failure -> CallToolResult(
        content = listOf(TextContent(text = message)),
        isError = true,
    )
}

private fun searchCodeSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        put("query", buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive("Natural-language or code-like search query"))
        })
        put("limit", buildJsonObject {
            put("type", JsonPrimitive("integer"))
            put("description", JsonPrimitive(
                "How many ranked results to return (default ${SearchCodeTool.DEFAULT_LIMIT}, " +
                    "min ${SearchCodeTool.MIN_LIMIT}, max ${SearchCodeTool.MAX_LIMIT})"
            ))
            put("default", JsonPrimitive(SearchCodeTool.DEFAULT_LIMIT))
            put("minimum", JsonPrimitive(SearchCodeTool.MIN_LIMIT))
            put("maximum", JsonPrimitive(SearchCodeTool.MAX_LIMIT))
        })
        put("include_snippet", buildJsonObject {
            put("type", JsonPrimitive("boolean"))
            put("description", JsonPrimitive(
                "If false, omit the source snippet text and return only locations + metadata"
            ))
            put("default", JsonPrimitive(SearchCodeTool.DEFAULT_INCLUDE_SNIPPET))
        })
    },
    required = listOf("query"),
)

private fun askCodebaseSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        put("query", buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive("Natural-language question about the codebase"))
        })
        put("top_k", buildJsonObject {
            put("type", JsonPrimitive("integer"))
            put("description", JsonPrimitive(
                "How many code chunks to retrieve as grounding context " +
                    "(default ${AskCodebaseTool.DEFAULT_TOP_K})"
            ))
            put("default", JsonPrimitive(AskCodebaseTool.DEFAULT_TOP_K))
            put("minimum", JsonPrimitive(AskCodebaseTool.MIN_TOP_K))
            put("maximum", JsonPrimitive(AskCodebaseTool.MAX_TOP_K))
        })
    },
    required = listOf("query"),
)
