package com.bugdigger.codeatlas.rag

/**
 * Which LLM backend the user has selected. Persisted verbatim in settings state.
 * API keys are NOT stored here; they live in [com.intellij.ide.passwordSafe.PasswordSafe].
 */
enum class LlmProviderKind {
    ANTHROPIC,
    OPENAI,
    OLLAMA,
}

/**
 * Fully-resolved provider configuration assembled at call time by the settings layer.
 * Holds secrets only for the duration of one answer call; not cached.
 */
sealed class LlmProvider {
    abstract val kind: LlmProviderKind
    abstract val model: String

    data class Anthropic(val apiKey: String, override val model: String) : LlmProvider() {
        override val kind: LlmProviderKind get() = LlmProviderKind.ANTHROPIC
    }

    data class OpenAI(val apiKey: String, override val model: String) : LlmProvider() {
        override val kind: LlmProviderKind get() = LlmProviderKind.OPENAI
    }

    /** [endpoint] example: `http://localhost:11434`. No API key required. */
    data class Ollama(val endpoint: String, override val model: String) : LlmProvider() {
        override val kind: LlmProviderKind get() = LlmProviderKind.OLLAMA
    }

    companion object {
        const val DEFAULT_ANTHROPIC_MODEL = "claude-3-5-sonnet-latest"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val DEFAULT_OLLAMA_ENDPOINT = "http://localhost:11434"
        const val DEFAULT_OLLAMA_MODEL = "llama3.1"
    }
}
