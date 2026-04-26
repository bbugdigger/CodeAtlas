# MCP Server Support — Design

**Status:** approved 2026-04-25 · awaiting implementation plan
**Scope:** v1 of MCP support inside the CodeAtlas IntelliJ plugin.

## Context

CodeAtlas already builds a per-project semantic index (`CodeAtlasIndexService`) and exposes two
high-level operations against it: `search(query, limit): List<RankedResult>` and
`AnswerGenerator.generate(query, chunks): Flow<AnswerToken>`. Both are surfaced in-IDE through
the tool window. **Outside** the IDE, that same index is invisible — Claude Desktop, Claude
Code CLI, Cursor, and other MCP-speaking hosts cannot query it.

This spec adds an embedded MCP server to the plugin so any local MCP host can call
`search_code` and `ask_codebase` against the currently-active project's CodeAtlas index. The
goal is to make CodeAtlas a useful retrieval/RAG backend for the user's other AI tooling
without duplicating the indexing pipeline outside the IDE.

## Locked design decisions

- **Server-only** MCP role. No client-side MCP support in v1.
- **Streamable HTTP transport** bound to `127.0.0.1`. Forced by architecture: the server must
  live inside the running IDE process to access `CodeAtlasIndexService`, so stdio (which
  requires the host to spawn the server as a child process) doesn't fit.
- **Two tools:** `search_code` and `ask_codebase`. They map 1:1 to the existing
  `CodeAtlasIndexService.search` and `AnswerGenerator.generate` entry points.
- **Single active project** per server. Tools take no project parameter; an
  `ActiveProjectTracker` resolves which open project to query at call time.
- **In-tree packaging.** New `mcp/` Kotlin package inside the existing plugin module — not a
  separate Gradle subproject, not a separate plugin distribution. The project is small enough
  that a new package buys all the isolation we need; we can split later if `mcp/` grows past
  ~5 files.
- **Default on.** The server starts when the plugin loads. Cost is one idle port + a small
  Ktor footprint; benefit is "it just works" once the user copies the host config snippet.

## Architecture

### New files

```
src/main/kotlin/com/bugdigger/codeatlas/mcp/
  McpServerService.kt        — application-scoped @Service, owns Ktor server lifecycle
  McpServerLifecycle.kt      — startup activity + shutdown disposable; reads settings
  ActiveProjectTracker.kt    — listens to ProjectManager.TOPIC + IdeFocusManager,
                               exposes currentProject(): Project?
  tools/
    SearchCodeTool.kt        — registers search_code; adapts RankedResult → CallToolResult
    AskCodebaseTool.kt       — registers ask_codebase; bridges Flow<AnswerToken> → MCP stream
    Dto.kt                   — @Serializable wire DTOs (decouples MCP wire format from CodeChunk)
```

### Modified files

- `settings/SettingsState.kt` — add `mcpEnabled: Boolean = true`, `mcpPort: Int = 64340`.
- `settings/CodeAtlasSettingsService.kt` — typed getters/setters mirroring existing pattern.
- `settings/CodeAtlasSettingsConfigurable.kt` — new "MCP Server" section (see UX below).
- `META-INF/plugin.xml` — register `McpServerService` (`<applicationService>`) and
  `McpServerLifecycle` (`<postStartupActivity>`).
- `build.gradle.kts` — add `io.modelcontextprotocol:kotlin-sdk-server:0.11.1` and
  `io.ktor:ktor-server-cio` (the SDK does not ship a Ktor engine; CIO is lightest for an
  embedded server).

### Server scope: application-scoped

One server per IDE instance, not per project. `ActiveProjectTracker` tells tools which
`CodeAtlasIndexService` to query. This mirrors how the official JetBrains MCP plugin works
(it powers the `mcp__jetbrains__*` tools in this very session) and avoids per-project port
allocation gymnastics.

### Shutdown discipline

`McpServerService` owns a `SupervisorJob() + Dispatchers.IO` scope. `Disposer.register(application, ...)`
runs `server.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)` and cancels the scope on
IDE shutdown so the port releases cleanly. Same pattern as the project's existing
`flushScope` and PSI-listener scope.

## Tool schemas & data flow

### `search_code`

**Input:**
```json
{
  "query":           { "type": "string" },
  "limit":           { "type": "integer", "default": 10, "minimum": 1, "maximum": 50 },
  "include_snippet": { "type": "boolean", "default": true }
}
```
Required: `["query"]`.

**Output:** single `TextContent` whose body is JSON-encoded `List<SearchResultDto>`:

```kotlin
@Serializable data class SearchResultDto(
    val path: String,           // project-relative when possible; absolute fallback
    val qualifiedName: String,
    val kind: String,           // CodeChunk.Kind.name
    val signature: String,
    val language: String,
    val score: Float,
    val startOffset: Int,
    val endOffset: Int,
    val snippet: String?        // only when include_snippet = true
)
```

**Flow:**
1. Resolve `project = ActiveProjectTracker.currentProject()` → MCP error if `null`.
2. Resolve `service = project.service<CodeAtlasIndexService>()`. If state is `Empty`, return
   MCP error: "Index not built yet — open the CodeAtlas tool window or wait for the initial
   build to finish".
3. Call `service.search(query, limit)` (already suspend; handler is suspend, no wrapping).
4. Map → `SearchResultDto`. Snippet text is read from VFS using the existing
   `ReadAction.compute { … }` pattern from `IndexBuilder`.
5. Return `CallToolResult(content = listOf(TextContent(Json.encodeToString(results))))`.

### `ask_codebase`

**Input:**
```json
{
  "query": { "type": "string" },
  "top_k": { "type": "integer", "default": 8, "minimum": 1, "maximum": 20 }
}
```
Required: `["query"]`.

**Output:** streamed during execution, then a final `CallToolResult` containing two
`TextContent` blocks: the full answer text and a JSON-encoded `sources` array (same shape as
`SearchResultDto` minus `snippet`).

**Flow:**
1. Resolve active project + service. Same error semantics as `search_code`.
2. Resolve LLM provider via `CodeAtlasSettingsService.resolveProvider()`. If `null`, return
   MCP error: "No LLM provider configured — open Settings → CodeAtlas".
3. `chunks = service.search(query, top_k).map { it.chunk }`.
4. Construct `KoogAnswerGenerator` per call (don't cache — same pattern as the tool window's
   Ask path; keeps the API key in memory only for the duration of one answer).
5. Collect `generator.generate(query, chunks)`:
   - `Delta(text)` → emit MCP progress notification (when SDK supports it for the call) **and**
     append to a `StringBuilder`.
   - `Done(sources)` → return final `CallToolResult` with accumulated text + serialized sources.
   - `Error(message)` → return `CallToolResult(isError = true, content = listOf(TextContent(message)))`.
     Message is already redacted by `KoogAnswerGenerator`.
6. **Cancellation:** if the host disconnects mid-stream, the SDK cancels the suspend handler;
   `flow.collect` throws `CancellationException` which cascades into the Koog `LLMClient`
   request. No leak.

### Cross-cutting

- **Serialization:** `kotlinx.serialization.json` (already on classpath via Koog). DTOs in
  `mcp/tools/Dto.kt` so the wire format is decoupled from internal types like `CodeChunk` —
  we can evolve one without breaking the other.
- **Error redaction:** all error strings flowing into `CallToolResult` go through the same
  redaction helper used by `KoogAnswerGenerator`. No leaked API keys, no leaked absolute
  paths outside the project root.
- **Path encoding:** `CodeChunk.virtualFileUrl` is normalized to a path relative to
  `project.basePath` when possible; absolute fallback only if the file is outside.

## Lifecycle, settings & UX

### Lifecycle

- `McpServerLifecycle` runs as a `<postStartupActivity>`. On first project open it calls
  `McpServerService.getInstance().ensureStarted()` which is idempotent.
- `McpServerService.start(port)` builds the Ktor `embeddedServer(CIO, host = "127.0.0.1", port = port) { mcpStreamableHttp { mcpServer } }`,
  registers both tools on `mcpServer`, and starts the server on the IO scope. If port bind
  fails, logs a warning, posts an IDE notification with "Change MCP port" action linking to
  Settings, and leaves the service in a "bind failed" state (non-fatal; rest of the plugin
  still works).
- On settings change (port or enable toggle), `Configurable.apply()` calls
  `McpServerService.restart()` which stops the old server and starts a new one.
- On IDE shutdown the registered Disposable stops the server and cancels the scope.

### `ActiveProjectTracker`

- Application-scoped singleton.
- Subscribes to `ProjectManager.TOPIC` for project open/close events.
- Subscribes to `IdeFocusManager` focus changes to update "current" when the user switches
  IDE windows.
- Maintains `@Volatile private var current: Project?`.
- On the current project closing, falls back to the most-recently-active still-open project
  (`ProjectManager.openProjects` is the source of truth; tracker keeps an LRU).
- `currentProject(): Project?` — returns null only when zero projects are open.
- Tools re-resolve via `currentProject()` on every call (no project caching across calls; a
  project may close between calls).

### Settings UI

New section "MCP Server" in `CodeAtlasSettingsConfigurable`:

- Checkbox: **Enable MCP server** (allows external AI hosts to query the index).
- Number field: **Port** — default `64340`, range `1024–65535`. (Sits one below the
  JetBrains MCP plugin's `64342+` range to minimize collision risk.)
- Read-only status label: `Listening on http://127.0.0.1:64340/mcp` /
  `Disabled` / `Port 64340 in use — pick another and apply`.
- Button: **Copy Claude Desktop config** — copies to clipboard:
  ```json
  {
    "mcpServers": {
      "codeatlas": { "url": "http://127.0.0.1:64340/mcp" }
    }
  }
  ```

Persistence: plain fields in `SettingsState` (no secrets, no PasswordSafe needed).

### Tool window indicator

`IndexStatusBar` (or a sibling component in the tool window header) gains a small
green/gray/red dot for MCP server status. Tooltip: `MCP server: listening on
http://127.0.0.1:64340/mcp · active project: <name>`.

## Testing strategy

### Unit tests (no IDE container)

- `mcp/tools/SearchCodeToolTest.kt` — fake `IndexService` returning canned `RankedResult`s;
  assert JSON output shape, snippet inclusion/exclusion, error path when index is empty.
- `mcp/tools/AskCodebaseToolTest.kt` — fake `AnswerGenerator` emitting a controlled
  `Flow<AnswerToken>`. Assert: deltas accumulate into final answer; sources serialize
  correctly; `Error` becomes `CallToolResult(isError = true)`; collector cancellation
  cancels the upstream flow.
- `mcp/DtoSerializationTest.kt` — round-trip `SearchResultDto` JSON to lock the wire format.

### Integration test (BasePlatformTestCase)

- `mcp/McpServerIntegrationTest.kt` — start `McpServerService` on a random ephemeral port,
  connect with `io.modelcontextprotocol:kotlin-sdk-client`, call `tools/list` (both tools
  present), call `search_code` against a fixture project (reuse fixtures from
  `KotlinLanguageAdapterTest`), call `ask_codebase` with a fake `AnswerGenerator`. Verify the
  streaming protocol round-trips end-to-end.

### `ActiveProjectTracker`

- `mcp/ActiveProjectTrackerTest.kt` — using `LightPlatformTestCase`, simulate
  open/close/focus events; assert `currentProject()` reflects the latest active project and
  falls back correctly.

### Plugin verifier

- `./gradlew verifyPlugin` — confirms no API misuse from the new application service or
  startup activity registration. Run before merging.

### Manual end-to-end

1. `./gradlew runIde`; open a Kotlin project; confirm Settings shows
   `Listening on http://127.0.0.1:64340/mcp`.
2. Click **Copy Claude Desktop config**; paste into
   `%APPDATA%/Claude/claude_desktop_config.json`; restart Claude Desktop.
3. In Claude Desktop: "search the codebase for the index service" → `search_code` is invoked,
   results render with file paths and signatures.
4. In Claude Desktop: "explain how the embedding cache works" → `ask_codebase` streams a
   Koog-generated answer with sources.
5. Toggle MCP off in Settings → status flips to `Disabled`; Claude Desktop tool calls fail
   with connection refused.
6. Open a second project window → status strip in both tool windows reflects the now-active
   project.

## Files where existing patterns get reused

- `index/CodeAtlasIndexService.kt:92` — `suspend fun search(...)` reused as-is.
- `rag/AnswerGenerator.kt` — `Flow<AnswerToken>` reused as-is.
- `rag/KoogAnswerGenerator.kt` — provider-resolution + error redaction patterns reused.
- `index/IndexBuilder.kt` — `ReadAction.compute { … }` boundary pattern for snippet extraction.
- `index/CodeAtlasIndexService.kt` — `flushScope` shutdown pattern for `McpServerService`.
- `settings/CodeAtlasSettingsService.kt` — typed getter/setter pattern for new settings.

## Out of scope for v1

- Client-side MCP (CodeAtlas calling external MCP servers).
- Multi-project parameterization (`project_id` tool argument).
- MCP `resources/` and `prompts/` capabilities (only `tools/` for v1).
- Authentication beyond localhost binding.
- Remote (non-localhost) deployment.
- `rebuild_index` / `get_index_status` admin tools.
- Per-project port allocation.

These are deliberate cuts; the architecture leaves clean seams for each.
