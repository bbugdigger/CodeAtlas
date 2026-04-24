plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "com.bugdigger"
version = "1.0-SNAPSHOT"

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
        }

        changeNotes = """
            Phase 1 skeleton: language adapters (Kotlin/Java), index service scaffold.
        """.trimIndent()
    }

    pluginVerification {
        ides {
            recommended()
        }
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
