# CodeAtlas — Natural-Language Code Navigation Plugin

## Context

Internship deliverable for JetBrains: build an AI-powered IntelliJ plugin. CodeAtlas lets a developer ask questions like "where is authentication implemented?" or "how does the payment flow work?" and get a ranked list of classes/methods/top-level functions in the open project, each one-click navigable via the standard IntelliJ navigation APIs.

**Problem it addresses.** In unfamiliar codebases, "find the right symbol" is a discovery problem, not a keyword problem. The IDE's existing search (`Ctrl+Shift+F`, Navigate by Class/File) only matches what you already know to type. Semantic retrieval closes that gap by matching *intent* to *implementation* even when identifier names don't overlap with the question.

**Phase 1 outcome (this plan).** A working IntelliJ IDEA plugin that indexes Kotlin/Java sources of the open project, produces vector embeddings via a locally-bundled ONNX model, and exposes a tool window where natural-language queries return a ranked list of navigable code symbols. Works fully offline — no API key, no network.

**Phase 2 (deferred, not built here).** Retrieval-augmented generation: feed top-K chunks to an LLM and render a natural-language answer with citations. Architecture in Phase 1 leaves a clean seam for this.

## Locked decisions (from brainstorm)

| Decision | Choice |
|---|---|
| Scope | Semantic search + navigation (Phase 1); RAG chat seam for Phase 2 |
| Languages | Kotlin + Java concrete; pluggable `LanguageAdapter` interface for future Python/TS/Go |
| Embeddings | Local ONNX model bundled in plugin resources |
| UI | Dedicated tool window (right side); no Search Everywhere, no right-click actions in Phase 1 |
| Koog | Not used in Phase 1 (no LLM calls to orchestrate). Re-evaluate for Phase 2. |
| Timeline | 3–4 weeks focused work |

## Architecture overview

Five layers, each a single-purpose unit communicating through narrow interfaces.

```
┌─────────────────────────────────────────────┐
│  UI  (Tool Window)                          │
│  • Search field + debounced input           │
│  • Ranked result cards + progress indicator │
└───────────────────┬─────────────────────────┘
                    │ calls
┌───────────────────▼─────────────────────────┐
│  Retriever                                  │
│  embed(query) → top-K vectors → rerank      │
└───────────┬───────────────┬─────────────────┘
            │ query         │ chunk vectors
┌───────────▼───────┐   ┌───▼─────────────────┐
│ EmbeddingProvider │   │ VectorStore + Cache │
│  OnnxEmbedding…   │   │  in-mem + disk      │
└───────────▲───────┘   └───▲─────────────────┘
            │                │ written by
┌───────────┴────────────────┴────────────────┐
│  IndexService                               │
│  • full index on startup                    │
│  • incremental re-index on PSI change       │
│  • uses LanguageAdapter per file            │
└───────────────────┬─────────────────────────┘
                    │ extracts via
┌───────────────────▼─────────────────────────┐
│  LanguageAdapter (Kotlin, Java)             │
│  PsiFile → List<CodeChunk>                  │
└─────────────────────────────────────────────┘
```

## Component responsibilities & file layout

Source root: `src/main/kotlin/com/bugdigger/codeatlas/`

### `index/` — orchestrates indexing and lifecycle
- `CodeChunk.kt` — immutable data class: `(id, qualifiedName, kind, signature, docComment, language, virtualFileUrl, startOffset, endOffset, containerFqn, contentHash)`. `kind` is an enum over `CLASS | INTERFACE | OBJECT | METHOD | FUNCTION | CONSTRUCTOR | DOC`.
- `CodeAtlasIndexService.kt` — `@Service(Service.Level.PROJECT)`; orchestrates indexing and exposes `suspend fun search(query: String, limit: Int): List<RankedResult>`. Owns the `VectorStore` and `PersistentCache` instances.
- `IndexBuilder.kt` — walks `ProjectFileIndex.iterateContent` in a `Task.Backgroundable`, routes files to the matching `LanguageAdapter`, batches chunks through the `EmbeddingProvider` (batch size 16), writes into the in-memory store and cache.
- `PersistentCache.kt` — single-file binary cache at `PathManager.getSystemPath()/CodeAtlas/<projectHash>/index.bin`. Header: `magic(4) | schemaVersion(2) | embeddingModelId(string) | dim(4)`. Record: `contentHash(32B) + vector(dim*4B) + chunk metadata (JSON line)`. On version mismatch → rebuild.
- `PsiChangeListener.kt` — implements `PsiTreeChangeListener`; debounces with a 1s coalescing window; pushes affected `PsiFile`s to a `CoroutineScope`-backed channel that the `IndexBuilder` drains in incremental mode.

### `language/` — PSI → chunks, pluggable per language
- `LanguageAdapter.kt` — interface:
  ```kotlin
  interface LanguageAdapter {
      fun supports(file: PsiFile): Boolean
      fun extract(file: PsiFile): List<CodeChunk>
  }
  ```
- `KotlinLanguageAdapter.kt` — uses Kotlin PSI (`KtFile`, `KtClass`, `KtNamedFunction`, `KtDeclaration`). Extracts classes, objects, top-level functions, member functions, KDoc blocks.
- `JavaLanguageAdapter.kt` — uses `PsiJavaFile`, `PsiClass`, `PsiMethod`, JavaDoc. Same chunk shape.
- `LanguageAdapters.kt` — registry/lookup; iterated in order until `supports(file)` returns true.

**Why this split**: Phase-2 readiness without cost — adding Python later is a single-file addition.

### `embedding/` — vectors from text
- `EmbeddingProvider.kt` — interface: `suspend fun embed(texts: List<String>): List<FloatArray>`; property `val dim: Int`; property `val modelId: String` (for cache invalidation).
- `OnnxEmbeddingProvider.kt` — wraps `ai.onnxruntime.OrtSession`. Loads model from plugin resources at first use. Tokenizes via `BertTokenizer`, runs inference, mean-pools the last hidden state, L2-normalizes. Single-threaded on a dedicated dispatcher (`Dispatchers.Default.limitedParallelism(1)`) to avoid ONNX contention.
- `tokenizer/BertTokenizer.kt` — WordPiece tokenizer. Either ship vocab.txt and hand-roll the ~80-line WordPiece loop, OR depend on `ai.djl.huggingface:tokenizers`. Preference: the DJL library if bundle size allows; else hand-roll.

**Model choice**: `BAAI/bge-small-en-v1.5` (384-dim, ~130MB fp32, ~33MB int8 quantized). Int8 quantized is preferred — quality loss is <2% on MTEB, and the plugin JAR stays lean.

### `search/` — ranking and scoring
- `VectorStore.kt` — in-memory `FloatArray` of shape `(N, dim)` flattened, plus a parallel `List<CodeChunk>`. `topK(queryVec, k)` runs a SIMD-friendly dot-product scan. Linear scan is fine up to ~10k chunks (<20ms). HNSW is a later upgrade if profiling shows it's needed.
- `Retriever.kt` — pipeline:
  1. embed query once
  2. `VectorStore.topK(queryVec, 50)` → candidate set
  3. `Reranker.rerank(candidates, query)` → final top 20
- `ScoringSignals.kt` — pure-function signal fusion. Final score = `w_vec * cosineSim + w_name * identifierSubstringMatch + w_kind * kindFitPrior + w_doc * hasDocBoost`. Weights start at `(0.7, 0.15, 0.05, 0.1)` and are plain constants (no learning). Tunable via a dev-mode setting.

### `ui/` — tool window
- `CodeAtlasToolWindowFactory.kt` — replaces the template `MyToolWindowFactory`. Registered in `plugin.xml`.
- `CodeAtlasToolWindow.kt` — root panel (BorderLayout). North: `SearchBar`. Center: a `JBSplitter` (vertical, proportion=1.0 in Phase 1) with the top component = `ResultListPanel` inside a `JBScrollPane` and the bottom component = an empty placeholder reserved for the Phase 2 answer panel. South: thin indexing-status strip.
- `SearchBar.kt` — `JBTextField` with a 300ms debounce (`javax.swing.Timer`). On `ENTER` or debounce tick, calls `IndexService.search` off-EDT via `AppExecutorUtil`/coroutines and posts results back to EDT.
- `ResultListPanel.kt` — virtualized `JBList` with a custom `ListCellRenderer` that produces a `ResultCard` per row.
- `ResultCard.kt` — renders: icon (from `IconManager` via chunk `kind`), bold qualified name, muted signature line, small file-path:line suffix, two-line snippet from the PSI range. Enter / double-click / button click → `OpenFileDescriptor(project, virtualFile, startOffset).navigate(true)` then `FileEditorManager.getInstance(project).selectedTextEditor` caret set.
- Indexing status: bound to `MessageBusConnection` topic `CodeAtlasIndexTopic`; shows "Indexing 412/1834" during full indexing, collapses to a small dot when idle.

### `settings/` — minimal
- `CodeAtlasSettings.kt` — `@State(name = "CodeAtlasSettings", storages = [@Storage("codeAtlas.xml")])`. Phase 1 fields: `includeTestSources: Boolean = false`, `cacheDirOverride: String? = null`.
- `SettingsConfigurable.kt` — registered `com.intellij.applicationConfigurable` (or project-level) with a single-form panel.

### `startup/` — lifecycle glue
- `CodeAtlasStartupActivity.kt` — implements `ProjectActivity` (coroutine-based startup, 2023.1+ API). Triggers initial index build; registers `PsiChangeListener`.

### `CodeAtlasBundle.kt`
Rename existing `MyMessageBundle.kt`; keep all user-facing strings here for future i18n.

## Data flow: the happy path

**Startup, first time opening a project**
1. `CodeAtlasStartupActivity.execute(project)` → `IndexService.ensureIndexed()`.
2. `PersistentCache.load(projectHash)` → miss.
3. `IndexBuilder.fullIndex()` launches `Task.Backgroundable`:
   - `ProjectFileIndex.iterateContent` yields source files.
   - Each file → matching `LanguageAdapter.extract` → list of `CodeChunk`.
   - Chunks batched (16) → `EmbeddingProvider.embed(chunkText)` where `chunkText = signature + "\n" + docComment + "\n" + containerFqn`.
   - Vectors + chunks written to `VectorStore` (in-memory) and `PersistentCache` (streamed to disk).
4. `PsiChangeListener` registered.

**Startup, subsequent opens**
1. Cache hit → `VectorStore.loadFromCache(cache)`. No embedding computation.
2. `PsiChangeListener` registered. Any files modified while the IDE was closed are caught by PSI's own stamp comparison + the index builder walks modified files once on startup.

**User query**
1. User types in `SearchBar` → debounce 300ms.
2. Dispatch to background: `IndexService.search(query, 20)`.
3. `EmbeddingProvider.embed([query])` → `FloatArray(dim)`.
4. `VectorStore.topK(q, 50)` → 50 candidates.
5. `Reranker.rerank(…)` → top 20.
6. Back on EDT: `ResultListPanel` re-renders.

**Navigation**
1. Enter/double-click on a `ResultCard` → `OpenFileDescriptor(project, vfile, chunk.startOffset).navigate(true)`.

**Incremental re-index**
1. User edits a `.kt` file → `PsiChangeListener.childrenChanged`.
2. Debounce coalesces bursts into 1s windows.
3. For each changed file: re-run `LanguageAdapter.extract` → diff new chunks vs cached chunks by `contentHash` → embed only net-new or changed → replace in `VectorStore` → flush delta to `PersistentCache`.

## Phase 2 seam (build in Phase 1, dormant)

- `AnswerGenerator.kt` interface in a new `rag/` package, with only `NoopAnswerGenerator` wired in.
- `Retriever` already returns `RankedResult(chunk, score, snippet, lineRange)` — precisely the shape a RAG prompt needs.
- `CodeAtlasToolWindow` already uses a `JBSplitter` whose bottom component is empty in Phase 1; Phase 2 fills that bottom component with a streaming answer panel and animates the divider down.
- When Phase 2 starts, the decision is: single generative call (direct Anthropic/OpenAI SDK, ~50 LOC) versus agentic tool-calling (Koog). If the UX stays as "answer once with citations", the direct SDK is simpler and Koog is unnecessary. Koog becomes warranted only if the LLM should autonomously invoke IDE tools in sequence (search → read file → find usages → answer).

## Files to modify or create

**Modify**
- `build.gradle.kts` — add `bundledPlugin("com.intellij.java")` and `bundledPlugin("org.jetbrains.kotlin")`; add `implementation("com.microsoft.onnxruntime:onnxruntime:<pin>")`; add `implementation("ai.djl.huggingface:tokenizers:<pin>")` (or skip if hand-rolled); add `testImplementation` for JUnit 5 & IntelliJ test framework extras as needed.
- `src/main/resources/META-INF/plugin.xml` — add `<depends>com.intellij.modules.java</depends>` and `<depends>org.jetbrains.kotlin</depends>`; register new tool window factory + startup activity + settings configurable + project-level service; update `<description>` and `<vendor>`.
- `src/main/kotlin/com/bugdigger/codeatlas/MyToolWindow.kt` — delete (replaced by `CodeAtlasToolWindow` + `CodeAtlasToolWindowFactory`).
- `src/main/kotlin/com/bugdigger/codeatlas/MyMessageBundle.kt` → rename `CodeAtlasBundle.kt`.
- `src/main/resources/messages/MyMessageBundle.properties` → rename `CodeAtlasBundle.properties`; seed with tool window title, search placeholder, indexing states.

**Create (source)**
- All files listed under "Component responsibilities & file layout" above.

**Create (resources)**
- `src/main/resources/model/bge-small-en-v1.5-int8.onnx` (or equivalent small ONNX file; document the source URL and license in a sibling `MODEL_CARD.md`)
- `src/main/resources/model/vocab.txt` (WordPiece vocab)

**Create (tests)** — under `src/test/kotlin/com/bugdigger/codeatlas/`
- `search/ScoringSignalsTest.kt` — pure-function tests of the signal fusion math.
- `search/VectorStoreTest.kt` — cosine-sim correctness, top-K ordering, cache round-trip.
- `language/KotlinLanguageAdapterTest.kt` — `LightJavaCodeInsightFixtureTestCase` subclass; fixture Kotlin file → assert extracted chunk shape.
- `language/JavaLanguageAdapterTest.kt` — same pattern for Java fixtures.
- `index/IndexBuilderIntegrationTest.kt` — end-to-end on a fixture project: index → run 5 known queries → assert expected chunks appear in top-3.
- `embedding/OnnxEmbeddingProviderTest.kt` — sanity test: output dim matches, identical input yields identical vector, vector is unit-normalized.

## Reused IntelliJ Platform APIs

| Need | API |
|---|---|
| Walk project sources, respect excludes | `ProjectFileIndex.iterateContent` |
| Background task with progress | `Task.Backgroundable`, `ProgressIndicator` |
| Navigate to symbol | `OpenFileDescriptor.navigate`, `FileEditorManager` |
| Incremental PSI change events | `PsiManager.getInstance().addPsiTreeChangeListener` |
| Project-level service | `@Service(Service.Level.PROJECT)` |
| Startup hook | `ProjectActivity` (registered as `com.intellij.postStartupActivity`) |
| Persistent plugin data dir | `PathManager.getSystemPath()` |
| Settings UI | `com.intellij.applicationConfigurable` + `@State`/`@Storage` |
| Tool window | `com.intellij.toolWindow` extension + `ToolWindowFactory` |
| Event bus for index status | `MessageBus` + topic constant |
| Off-EDT dispatch | `AppExecutorUtil` or Kotlin coroutines on `Dispatchers.Default` |

## Execution order (3–4 week plan)

**Week 1 — Skeleton + extraction**
1. Copy this plan to `devdocs/plan.md` (the user's requested location).
2. Rename `MyMessageBundle` → `CodeAtlasBundle`; remove template tool window.
3. Wire `build.gradle.kts`: add `com.intellij.java` + Kotlin bundled plugin dependencies; add ONNX + tokenizer dependencies.
4. Build `CodeChunk` + `LanguageAdapter` interface + `KotlinLanguageAdapter` + `JavaLanguageAdapter` with unit tests.
5. Build `CodeAtlasIndexService` stub + `IndexBuilder.fullIndex()` scaffold that extracts chunks but does not yet embed. Smoke test: count chunks on a fixture project.

**Week 2 — Embeddings + retrieval**
6. Add ONNX model file + tokenizer. Implement `OnnxEmbeddingProvider` + tests (dim, determinism, unit norm).
7. Implement `VectorStore` + `PersistentCache` + tests (round-trip, version invalidation).
8. Implement `Retriever` + `ScoringSignals` + `Reranker` + tests (pure-function signal math).
9. End-to-end wire-up: `IndexBuilder.fullIndex()` now actually embeds and populates the store.

**Week 3 — UI + lifecycle**
10. Build `CodeAtlasToolWindowFactory`, `CodeAtlasToolWindow`, `SearchBar`, `ResultListPanel`, `ResultCard`. Debounce, async search, EDT-safe rendering.
11. Implement navigation on result click (`OpenFileDescriptor`).
12. `CodeAtlasStartupActivity` triggers `IndexService.ensureIndexed()` on project open with `Task.Backgroundable` progress UI.
13. `PsiChangeListener` + incremental re-index with debounced coalescing.
14. Indexing status bus + UI indicator.

**Week 4 — Polish + verification**
15. Settings panel (`includeTestSources`).
16. Integration test: full index + 5 fixed queries against a fixture project.
17. Run against a real small Kotlin codebase (the plugin itself + one public repo) — tune scoring weights.
18. Run `verifyPlugin` Gradle task; fix any reported issues.
19. Write `README.md` section with screenshots and usage, plus `devdocs/plan.md` → move to project root if appropriate.

## Verification — how we know it works end-to-end

- **Build + run**: `./gradlew runIde` (via the existing `Run IDE with Plugin` run configuration) opens a sandbox IDE with the plugin loaded.
- **Manual smoke on a real project**:
  1. Open a small Kotlin/Java project (recommend the plugin's own repo or a simple Spring Boot sample).
  2. Wait for "Indexing… N/M" status to finish.
  3. Search: "where is authentication done" / "entry point" / "tool window" / "how is indexing triggered" → expect relevant symbols in top-5.
  4. Click a result → IDE jumps to the exact PSI range.
  5. Modify a file (rename a class) → within ~2s, the new name appears in search; the old one does not.
  6. Close and reopen the project → no re-index runs (cache hit).
- **Automated**: `./gradlew test` runs unit + `BasePlatformTestCase` integration tests. `./gradlew verifyPlugin` must pass.
- **JetBrains MCP verification** (during development): use `mcp__jetbrains__build_project` after each change; `mcp__jetbrains__execute_run_configuration` on `Run IDE with Plugin`; `mcp__jetbrains__get_file_problems` to catch inspection issues before build.

## Out of scope for Phase 1 (explicit non-goals)

- Answering questions with an LLM (Phase 2).
- Languages other than Kotlin/Java (interface exists; implementations later).
- Search Everywhere integration, right-click "ask about this" actions.
- Remote embedding providers (interface exists; implementations later).
- HNSW / ANN vector index (linear scan is sufficient for target scale).
- Multi-project/workspace-wide search.
- Commit-message / VCS history signal in ranking.
- Marketplace publishing.

## Open risks and mitigations

| Risk | Mitigation |
|---|---|
| ONNX + tokenizer bundle is too large (>50MB plugin JAR) | Use int8-quantized bge-small (~33MB). If still too large, download-on-first-run to `PathManager.getSystemPath()` with a progress UI. |
| Embedding latency slows typing | Embed on a single dedicated dispatcher; debounce UI; cap query length. |
| Kotlin PSI API differences across IDE versions | Pin to `intellijIdea("2025.2.4")` for Phase 1; verify with `verifyPlugin` before any version bumps. |
| Initial index on a huge project blocks | `Task.Backgroundable` + progress + user can close the IDE; on reopen we resume from partial cache. |
| Ranking quality is weak on vague queries | Signal fusion weights are plain constants, easy to tune; keep a small hand-labeled eval set of 20 queries → expected top-3 for regression-testing tuning changes. |
