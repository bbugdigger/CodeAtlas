# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Canonical docs (read these first)

- `AGENTS.md` — coding standards, single-test patterns, threading rules, dependency policy. Treat as authoritative for *how to write code here*.
- `devdocs/plan.md` — full architecture, data flow, and Phase 1/2 scope. Treat as authoritative for *why the layers are shaped the way they are*. Note that the current code has progressed past the Phase 1 cutoff described there: `rag/` is wired in (Koog-backed answer generation), the tool window has an `AnswerPanel`, and settings include LLM provider config. Treat plan.md as design intent, not current truth.
- `README.md` is the unmodified IntelliJ Platform Plugin Template README — ignore it for project info.

This file only adds the cross-cutting facts that aren't obvious from a single file.

## Build / test / run

Wrapper-pinned, JVM 21, Gradle Kotlin DSL. On Windows use `gradlew.bat`; on macOS/Linux use `./gradlew`.

- Build everything: `./gradlew build`
- All tests: `./gradlew test`
- Single test class: `./gradlew test --tests "com.bugdigger.codeatlas.search.VectorStoreTest"`
- Single test method: `./gradlew test --tests "com.bugdigger.codeatlas.search.VectorStoreTest.topKReturnsHighestCosineInDescendingOrder"`
- Sandbox IDE with the plugin: `./gradlew runIde` (or the `Run IDE with Plugin` run config in `.run/`)
- Plugin verifier (run before substantial changes): `./gradlew verifyPlugin`
- Plugin ZIP: `./gradlew buildPlugin`

There is no `ktlint`/`detekt` configured; `./gradlew check` is the baseline quality gate.

## Architecture — what spans multiple files

### Five-layer pipeline
`UI → Retriever → {EmbeddingProvider, VectorStore+PersistentCache} ← IndexService ← LanguageAdapter (Kotlin/Java)`. Each layer talks through a narrow interface so a Phase-2 expansion (new language, new embedder, new answer backend) is a single-file addition.

### Index state machine (cross-cuts UI ↔ index)
`CodeAtlasIndexService` is the single owner of `chunks`/`vectors`/`store`, all guarded by one `lock`. State transitions are published on the `CODE_ATLAS_INDEX_TOPIC` message bus topic as a sealed `IndexState` (`Empty | BuildingFullIndex | Updating | Ready`). UI components (`IndexStatusBar`, tool window) subscribe — never poll. When you add an indexing path, publish an `IndexState` so the status strip stays accurate.

### Embedder swap = cache wipe (invariant)
`CodeAtlasIndexService.embedder` has a custom setter that clears `chunks`/`vectors`/`store` and emits `IndexState.Empty`. `PersistentCache` keys on `(modelId, dim, schemaVersion)` and silently invalidates on mismatch. Consequence: anywhere you change embedding output (model swap, dim change, input format change in `CodeChunk.embeddingInput`), bump `modelId` or `SCHEMA_VERSION` in `PersistentCache.kt` to force a clean rebuild — don't try to migrate cache contents.

The default embedder is `HashEmbeddingProvider` (deterministic, no model file). `OnnxEmbeddingProvider` (BGE-small int8) exists but is not wired by default — it lazily downloads the model on first use.

### Incremental re-index path
PSI edits → `CodeAtlasPsiChangeListener` (1s coalescing window, coroutine channel) → `IndexService.onFileChanged(vfile)` → `IndexBuilder.extractAndEmbedForFile` → swap that file's chunks atomically under `lock` → flush the whole index to `PersistentCache`. The cache is rewritten in full each time; there is no delta format.

### Retrieval scoring
`Retriever` always pulls `max(limit*3, 50)` candidates from `VectorStore.topK` before re-ranking. Final score in `ScoringSignals` is a fixed-weight linear combination of cosine similarity, identifier substring match, kind/intent fit, doc-presence, and an optional **stub-index boost** (`StubIndexSignal` checks the IDE stub index for FQN matches against query tokens). Weights are plain `const`s — no learning. Tune them against a hand-labeled eval set.

### RAG path (active, post-Phase-1)
Tool window's "Ask" button → `IndexService.search(query, ANSWER_TOP_K=8)` → `KoogAnswerGenerator.generate` streams `AnswerToken.Delta`/`Done`/`Error`. Provider is resolved per-call from `CodeAtlasSettingsService.resolveProvider()` and is **not cached** (the API key lives in memory only for the duration of one answer). Errors are redacted before reaching the UI.

### Secret storage
API keys (Anthropic, OpenAI) live in IntelliJ's `PasswordSafe`, never in `codeAtlas.xml`. Non-secret settings (provider choice, model id, Ollama endpoint, `includeTestSources`, `cacheDirOverride`) are in `SerializablePersistentStateComponent`. When adding a new credential, mirror the `getApiKey`/`setApiKey` pattern in `CodeAtlasSettingsService`.

### Threading contract
- PSI/VFS reads → wrap in `ReadAction.run` / `ReadAction.compute` (see `IndexBuilder.collectSourceFiles` and `KoogAnswerGenerator.resolveSources`).
- Long indexing → `Task.Backgroundable` + `ProgressIndicator`, driven by `requestFullIndex()`.
- UI updates from background coroutines → `SwingUtilities.invokeLater`.
- Each tool window owns a `SupervisorJob` scope cancelled on `dispose`. Search and Ask jobs cancel the previous in-flight job before starting — never queue stale work.

## Files where the architecture is enforced

- `index/CodeAtlasIndexService.kt` — ownership of state, lock discipline, message-bus publishing.
- `index/IndexBuilder.kt` — `ProjectFileIndex.iterateContent` walk, batch embedding (size 16), `ReadAction` boundaries.
- `index/PersistentCache.kt` — binary cache format; bump `SCHEMA_VERSION` if `CodeChunk` shape changes.
- `language/LanguageAdapters.kt` — registry; new language = add one entry here plus a `LanguageAdapter` impl.
- `search/Retriever.kt` + `search/ScoringSignals.kt` — the candidate pool sizing and weight constants.
- `rag/KoogAnswerGenerator.kt` — stream contract, error redaction, `LLMClient` lifetime.
- `settings/CodeAtlasSettingsService.kt` — split between XML state and `PasswordSafe`.

## When in doubt

- Editing PSI/index code — re-read `index/CodeAtlasIndexService.kt` for the lock+publish discipline; don't touch `chunks`/`vectors` outside `synchronized(lock)`.
- Adding a setting — add it to `SettingsState`, expose a typed `var` getter/setter on the service, and surface it in `CodeAtlasSettingsConfigurable`. Use `PasswordSafe` if it's a secret.
- Adding a new embedding model — bump `modelId` (cache will invalidate). If output dim changes, also rebuild every cache by changing `SCHEMA_VERSION`.
- Adding a new language — implement `LanguageAdapter`, register in `LanguageAdapters.kt`, add a fixture test under `language/` modeled on `KotlinLanguageAdapterTest.kt`.
