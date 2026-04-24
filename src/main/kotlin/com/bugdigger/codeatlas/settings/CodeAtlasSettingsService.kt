package com.bugdigger.codeatlas.settings

import com.bugdigger.codeatlas.rag.LlmProvider
import com.bugdigger.codeatlas.rag.LlmProviderKind
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Project-level persistent settings.
 *
 * Non-secret values (provider choice, model ids, endpoint) are stored in `codeAtlas.xml`.
 * API keys are stored in IntelliJ's [PasswordSafe] under per-provider credential attributes
 * and are NEVER written to XML state. See [getApiKey] / [setApiKey].
 */
@State(name = "CodeAtlasSettings", storages = [Storage("codeAtlas.xml")])
@Service(Service.Level.PROJECT)
class CodeAtlasSettingsService :
    SerializablePersistentStateComponent<CodeAtlasSettingsService.SettingsState>(SettingsState()) {

    data class SettingsState(
        val includeTestSources: Boolean = false,
        val cacheDirOverride: String = "",
        val llmProvider: LlmProviderKind = LlmProviderKind.ANTHROPIC,
        val anthropicModel: String = LlmProvider.DEFAULT_ANTHROPIC_MODEL,
        val openAiModel: String = LlmProvider.DEFAULT_OPENAI_MODEL,
        val ollamaEndpoint: String = LlmProvider.DEFAULT_OLLAMA_ENDPOINT,
        val ollamaModel: String = LlmProvider.DEFAULT_OLLAMA_MODEL,
    )

    var includeTestSources: Boolean
        get() = state.includeTestSources
        set(value) {
            updateState { it.copy(includeTestSources = value) }
        }

    var cacheDirOverride: String?
        get() = state.cacheDirOverride.trim().ifEmpty { null }
        set(value) {
            updateState { it.copy(cacheDirOverride = value?.trim().orEmpty()) }
        }

    var llmProvider: LlmProviderKind
        get() = state.llmProvider
        set(value) {
            updateState { it.copy(llmProvider = value) }
        }

    var anthropicModel: String
        get() = state.anthropicModel.ifBlank { LlmProvider.DEFAULT_ANTHROPIC_MODEL }
        set(value) {
            updateState { it.copy(anthropicModel = value.trim()) }
        }

    var openAiModel: String
        get() = state.openAiModel.ifBlank { LlmProvider.DEFAULT_OPENAI_MODEL }
        set(value) {
            updateState { it.copy(openAiModel = value.trim()) }
        }

    var ollamaEndpoint: String
        get() = state.ollamaEndpoint.ifBlank { LlmProvider.DEFAULT_OLLAMA_ENDPOINT }
        set(value) {
            updateState { it.copy(ollamaEndpoint = value.trim()) }
        }

    var ollamaModel: String
        get() = state.ollamaModel.ifBlank { LlmProvider.DEFAULT_OLLAMA_MODEL }
        set(value) {
            updateState { it.copy(ollamaModel = value.trim()) }
        }

    /** Reads the API key for [kind] from [PasswordSafe]. Returns null if unset or not applicable. */
    fun getApiKey(kind: LlmProviderKind): String? {
        if (kind == LlmProviderKind.OLLAMA) return null
        val password = PasswordSafe.instance.getPassword(credentialAttributes(kind))
        return password?.takeIf { it.isNotBlank() }
    }

    /** Persists [apiKey] for [kind] (blank clears). No-op for Ollama. */
    fun setApiKey(kind: LlmProviderKind, apiKey: String?) {
        if (kind == LlmProviderKind.OLLAMA) return
        val attrs = credentialAttributes(kind)
        if (apiKey.isNullOrBlank()) {
            PasswordSafe.instance.set(attrs, null)
        } else {
            PasswordSafe.instance.set(attrs, Credentials(KEY_USER, apiKey))
        }
    }

    /**
     * Builds a fully-resolved [LlmProvider] for the currently selected provider, or null if the
     * required credentials are missing. The returned instance holds the API key in memory and
     * should not be cached beyond the lifetime of one answer call.
     */
    fun resolveProvider(): LlmProvider? = when (llmProvider) {
        LlmProviderKind.ANTHROPIC -> getApiKey(LlmProviderKind.ANTHROPIC)?.let {
            LlmProvider.Anthropic(it, anthropicModel)
        }
        LlmProviderKind.OPENAI -> getApiKey(LlmProviderKind.OPENAI)?.let {
            LlmProvider.OpenAI(it, openAiModel)
        }
        LlmProviderKind.OLLAMA -> LlmProvider.Ollama(ollamaEndpoint, ollamaModel)
    }

    private fun credentialAttributes(kind: LlmProviderKind): CredentialAttributes =
        CredentialAttributes(generateServiceName(SERVICE_NAMESPACE, "apiKey.${kind.name}"))

    private companion object {
        const val SERVICE_NAMESPACE = "CodeAtlas"
        const val KEY_USER = "apiKey"
    }
}
