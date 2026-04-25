plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
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
        intellijIdea("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Kotlin PSI + Java PSI are the two language frontends we extract chunks from in Phase 1.
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
    }

    // ONNX Runtime for running the bundled embedding model locally in the IDE process.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")

    // HuggingFace tokenizer via DJL — required by the ONNX model input pipeline (WordPiece / BPE).
    implementation("ai.djl.huggingface:tokenizers:0.31.0")

    // Koog — JetBrains LLM orchestration framework (Phase 2 RAG layer). Pinned; swap behind AnswerGenerator interface.
    // Exclude the standard kotlinx-coroutines artifact so we use IntelliJ's patched build instead (which adds
    // `runBlockingWithParallelismCompensation` required by the IDE test framework).
    implementation("ai.koog:koog-agents:0.7.3") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }

    // JUnit 4 for unit tests; BasePlatformTestCase / LightJavaCodeInsightFixtureTestCase use JUnit 3/4 style.
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
            // Cap at the 2025.2 minor series. When 2026.1 ships, run verifyPlugin
            // against it, then bump this to the next series cap (e.g. "261.*").
            untilBuild = "252.*"
        }

        changeNotes = """
            <h3>1.0.0 — Initial release</h3>
            <ul>
              <li>Semantic code search over Kotlin and Java sources, powered by a
                  locally-bundled ONNX embedding model (BGE-small INT8). No network
                  calls during search.</li>
              <li>Right-click <em>Ask CodeAtlas</em> in the editor to query the
                  current selection or symbol at the caret.</li>
              <li>Optional retrieval-augmented answers with citation-clickable
                  sources. Bring your own provider — Anthropic, OpenAI, or local
                  Ollama. API keys live in IntelliJ's PasswordSafe.</li>
              <li>Tools menu actions: <em>Focus Search</em>, <em>Rebuild Index</em>,
                  <em>Clear Cache and Rebuild</em>.</li>
              <li>Per-project, per-model persistent cache that survives IDE
                  restarts and embedder swaps.</li>
              <li>Settings: provider configuration with live <em>Test connection</em>
                  buttons, includeTestSources, cache directory override.</li>
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
