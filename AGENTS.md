# AGENTS.md

Agent guidance for working in `CodeAtlas` (IntelliJ Platform plugin, Kotlin/JVM).

## Project Snapshot

- Language: Kotlin (JVM target 21), plus Java plugin APIs.
- Build tool: Gradle Kotlin DSL (`build.gradle.kts`).
- Plugin target: IntelliJ IDEA `2025.2.4` via `org.jetbrains.intellij.platform`.
- Test stack: JUnit 4 + IntelliJ test framework (`BasePlatformTestCase` style).
- Key source roots:
  - `src/main/kotlin/com/bugdigger/codeatlas`
  - `src/test/kotlin/com/bugdigger/codeatlas`
  - `src/main/resources/META-INF/plugin.xml`

## Environment and Setup

- Prefer wrapper commands so tool versions stay pinned.
- macOS/Linux commands use `./gradlew`.
- Windows commands use `./gradlew.bat` (or `gradlew.bat` in `cmd`).
- Gradle caches/configuration cache are enabled in `gradle.properties`.

## Build, Test, Verify Commands

### Core Commands

- Build everything: `./gradlew build`
- Run tests only: `./gradlew test`
- Run all verification checks: `./gradlew check`
- Build plugin ZIP: `./gradlew buildPlugin`
- Run plugin in sandbox IDE: `./gradlew runIde`
- Verify plugin compatibility/structure: `./gradlew verifyPlugin`
- Validate plugin project configuration: `./gradlew verifyPluginProjectConfiguration`
- Clean outputs: `./gradlew clean`

### Single Test Execution (Important)

- Run one test class:
  - `./gradlew test --tests "com.bugdigger.codeatlas.search.VectorStoreTest"`
- Run one test method:
  - `./gradlew test --tests "com.bugdigger.codeatlas.search.VectorStoreTest.topKReturnsHighestCosineInDescendingOrder"`
- Run one package pattern:
  - `./gradlew test --tests "com.bugdigger.codeatlas.search.*"`
- IntelliJ fixture tests can be targeted the same way:
  - `./gradlew test --tests "com.bugdigger.codeatlas.language.KotlinLanguageAdapterTest.testExtractsClassMethodAndTopLevelFunction"`

### Useful Dev Commands

- List available tasks: `./gradlew tasks --all`
- Rebuild from scratch: `./gradlew clean build`
- Run a focused compile check: `./gradlew compileKotlin compileTestKotlin`

## Linting and Static Analysis

- There is no dedicated `ktlint`/`detekt` task configured right now.
- Treat `./gradlew check` as the baseline CI-style quality gate.
- Use IntelliJ inspections for style/smell checks when editing.
- For plugin-specific correctness, run `./gradlew verifyPlugin` before finalizing substantial changes.

## Repository-Specific Coding Standards

These conventions are inferred from the current codebase and should be preserved.

### Imports

- Use explicit imports only; do not introduce wildcard imports.
- Keep imports grouped by package with stable ordering (stdlib/third-party/IntelliJ/project).
- Remove unused imports promptly.

### Formatting and Layout

- Use 4-space indentation; no tabs.
- Keep line width reasonable for readability in JetBrains IDEs.
- Use trailing commas for multiline argument/parameter lists and data class construction.
- Prefer expression-bodied functions when short and clear.
- Preserve existing brace style and whitespace patterns in surrounding files.

### Kotlin Language Style

- Prefer `val` by default; use `var` only when mutation is required.
- Use null-safety idioms (`?:`, safe calls, early returns) instead of `!!`.
- Use `data class` for immutable value carriers (e.g., `CodeChunk`, result DTOs).
- Keep public API signatures explicit (return types and key property types).
- Use `companion object` for constants and factory helpers.

### Naming Conventions

- Packages: lowercase, dot-separated (`com.bugdigger.codeatlas.*`).
- Types: `PascalCase` (`CodeAtlasIndexService`, `VectorStore`).
- Functions/properties: `camelCase` (`extractAndEmbedForFile`, `chunkCount`).
- Constants: `UPPER_SNAKE_CASE` (`MIN_CANDIDATE_POOL`, `W_VECTOR`).
- Test methods: descriptive `camelCase`; fixture tests may keep `test...` naming.

### Control Flow and Readability

- Prefer guard clauses for invalid/empty inputs.
- Keep functions focused; extract helpers for parsing/scoring/serialization.
- Keep side-effect boundaries explicit (I/O, cache writes, message bus publishes).

### Error Handling and Validation

- Validate invariants with `require(...)` for programmer errors/preconditions.
- For boundary I/O that can legitimately fail (cache read), fail soft and recover.
- Avoid swallowing exceptions unless there is a documented fallback path.
- In plugin runtime paths, prefer resilience over hard failure when possible.

### Concurrency and Threading

- Be explicit about thread context:
  - background work on coroutines/Gradle tasks,
  - UI updates on EDT (`SwingUtilities.invokeLater`).
- Protect shared mutable state with existing lock strategy (`synchronized(lock)`).
- Preserve `@Volatile` usage where currently used for cross-thread visibility.
- Cancel/replace in-flight jobs for debounced search flows instead of queuing stale work.

### IntelliJ Platform Patterns

- Keep project services in `@Service(Service.Level.PROJECT)` classes.
- Use `ReadAction` for PSI/VFS reads.
- Use `Task.Backgroundable` + `ProgressIndicator` for long-running indexing operations.
- Navigate via `OpenFileDescriptor` and `VirtualFileManager` patterns already in use.
- Register plugin extensions in `src/main/resources/META-INF/plugin.xml`.

## Testing Guidelines

- Add/adjust unit tests for behavior changes in:
  - `search/` scoring/retrieval,
  - `index/` cache/index lifecycle,
  - `language/` PSI extraction.
- Keep tests deterministic; avoid external network dependencies.
- Prefer small focused test data over large fixtures.
- For bug fixes, add a regression test that fails before the fix.

## Dependency and Build Configuration Rules

- Do not change IntelliJ platform version or plugin IDs casually.
- Keep Kotlin/JVM target aligned with current Gradle config (Java 21, JVM 21).
- New dependencies should be justified by clear runtime value and plugin size impact.
- For embedding/model changes, preserve cache compatibility strategy (`modelId`, `dim`, schema checks).

## Files and Architecture Awareness

- Index lifecycle and orchestration: `index/CodeAtlasIndexService.kt`, `index/IndexBuilder.kt`.
- Embedding providers: `embedding/` (hash provider default, ONNX provider optional path).
- Retrieval pipeline: `search/VectorStore.kt`, `search/Retriever.kt`, `search/ScoringSignals.kt`.
- Language extraction adapters: `language/KotlinLanguageAdapter.kt`, `language/JavaLanguageAdapter.kt`.
- Tool window UI: `ui/CodeAtlasToolWindow.kt` and related components.

## Cursor/Copilot Rule Integration

- Checked for Cursor rules in `.cursor/rules/` and `.cursorrules`: none found.
- Checked for Copilot instructions in `.github/copilot-instructions.md`: none found.
- If these files are added later, treat them as higher-priority supplements to this document.

## Agent Workflow Recommendations

- Before edits: inspect neighboring files for local conventions.
- During edits: keep diffs minimal and architecture-consistent.
- After edits (minimum): run targeted tests.
- After substantial changes: run `./gradlew test` and `./gradlew verifyPlugin`.
- Before handing off: note any unrun checks or environment-specific limitations.
