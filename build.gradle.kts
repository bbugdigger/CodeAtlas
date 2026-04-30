plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    // Generates `.serializer()` factories for @Serializable classes used by the MCP wire DTOs.
    // Pinned to the same Kotlin version as the kotlin-jvm plugin to keep compiler plugins consistent.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.15.0"
}

group = "com.bugdigger"
// Override on the command line for snapshot builds: `-Pversion=1.0.1-SNAPSHOT`
version = (project.findProperty("version") as? String).takeUnless { it == "unspecified" } ?: "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Kotlin PSI + Java PSI are the two language frontends we extract chunks from in Phase 1.
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
    }

    // ONNX Runtime for running the bundled embedding model locally in the IDE process.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")

    // HuggingFace tokenizer via DJL — required by the ONNX model input pipeline (WordPiece / BPE).
    implementation("ai.djl.huggingface:tokenizers:0.31.0")

    // Model Context Protocol — server SDK + Ktor CIO engine for the embedded localhost HTTP transport.
    // The SDK does not ship a Ktor engine; CIO is the lightest server engine that works for an embedded
    // service.
    //
    // Exclude the standard kotlinx-coroutines artifact so we keep using IntelliJ's patched coroutines
    // build, which provides `runBlockingWithParallelismCompensation` required by the IDE test framework.
    // Without this, BasePlatformTestCase tests fail at app boot with NoSuchMethodError on that symbol.
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.11.1") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    implementation("io.ktor:ktor-server-cio:3.0.3") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }

    // JUnit 4 for unit tests; BasePlatformTestCase / LightJavaCodeInsightFixtureTestCase use JUnit 3/4 style.
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Wildcards are not allowed in sinceBuild; use a concrete platform build
            // (matches the IDE we develop against — IU-2026.1.1 = build 261.23567).
            sinceBuild = "261"
            // Tracking the 2026.1 minor series; bump on the next IDEA release.
            untilBuild = "261.*"
        }

        changeNotes = """
            <h3>1.1.0</h3>
            <ul>
              <li>Search Everywhere integration: press <em>Shift-Shift</em>, switch to the
                  <em>Code Atlas</em> tab, and search the project's semantic index without
                  leaving the editor.</li>
              <li>Removed the right-side tool window. Index status now lives as a small
                  widget in the IDE status bar; click it to open Search Everywhere or
                  to trigger a rebuild.</li>
              <li>Removed the optional remote-LLM "Ask" feature, the associated provider
                  settings (Anthropic / OpenAI / Ollama, model fields, API-key entry, test
                  buttons), and the corresponding MCP <code>ask_codebase</code> tool.
                  CodeAtlas is now fully offline.</li>
              <li>Old API keys remain in IntelliJ's PasswordSafe and can be cleared via
                  Settings → Appearance &amp; Behavior → System Settings → Passwords.</li>
              <li>Compatible with IntelliJ Platform 2026.1.</li>
            </ul>
            <h3>1.0.0 — Initial release</h3>
            <ul>
              <li>Semantic code search over Kotlin and Java sources, powered by a
                  locally-bundled ONNX embedding model (BGE-small INT8). No network
                  calls during search.</li>
              <li>Tools menu actions: <em>Focus Search</em>, <em>Rebuild Index</em>,
                  <em>Clear Cache and Rebuild</em>.</li>
              <li>Per-project, per-model persistent cache that survives IDE
                  restarts and embedder swaps.</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    // Signing + publishing read all credentials from environment variables so
    // nothing secret enters the build script or git history. Set these before
    // running `signPlugin` / `publishPlugin`:
    //   CERTIFICATE_CHAIN          - PEM-encoded chain (multiline, paste verbatim)
    //   PRIVATE_KEY                - PEM-encoded private key (multiline)
    //   PRIVATE_KEY_PASSWORD       - passphrase for the private key
    //   PUBLISH_TOKEN              - JetBrains Marketplace upload token
    // See https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    test {
        // BasePlatformTestCase tests use JUnit 3/4-style runners.
        useJUnit()
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
