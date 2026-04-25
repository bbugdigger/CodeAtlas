package com.bugdigger.codeatlas.rag

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider as KoogLLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import com.bugdigger.codeatlas.index.CodeChunk
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlin.time.ExperimentalTime

/**
 * [AnswerGenerator] backed by the Koog prompt-executor clients.
 *
 * Constructs a short-lived [LLMClient] per call, streams [StreamFrame.TextDelta] events,
 * and maps them to [AnswerToken.Delta]. Terminates with [AnswerToken.Done] on stream end
 * or [AnswerToken.Error] on any exception (with API key redaction).
 *
 * Source text for each chunk is resolved lazily under a [ReadAction] from the VFS.
 */
@OptIn(ExperimentalTime::class)
class KoogAnswerGenerator(
    private val providerSupplier: () -> LlmProvider,
) : AnswerGenerator {

    override fun generate(query: String, chunks: List<CodeChunk>): Flow<AnswerToken> = flow {
        val provider = providerSupplier()
        val retrieved = resolveSources(chunks)
        val built = PromptBuilder.build(query, retrieved)

        val (client, model) = buildClient(provider)
        try {
            val koogPrompt = Prompt.build("codeatlas-rag") {
                system(built.system)
                user(built.user)
            }
            val frames: Flow<StreamFrame> = client.executeStreaming(koogPrompt, model)
            frames.collect { frame ->
                if (frame is StreamFrame.TextDelta && frame.text.isNotEmpty()) {
                    emit(AnswerToken.Delta(frame.text))
                }
                // Ignore reasoning, tool calls, End; Done is emitted after collect completes.
            }
            emit(AnswerToken.Done(built.includedChunks))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            emit(AnswerToken.Error(redact(t.message ?: t::class.simpleName ?: "LLM call failed", provider)))
        } finally {
            runCatching { client.close() }
        }
    }.catch { t ->
        // Defensive: should not normally reach here, but keep the stream well-formed.
        emit(AnswerToken.Error("Unexpected error: ${t::class.simpleName}"))
    }

    private fun resolveSources(chunks: List<CodeChunk>): List<RetrievedChunk> {
        if (chunks.isEmpty()) return emptyList()
        return ReadAction.compute<List<RetrievedChunk>, RuntimeException> {
            val vfm = VirtualFileManager.getInstance()
            chunks.map { chunk ->
                val file = vfm.findFileByUrl(chunk.virtualFileUrl)
                val text = if (file != null && file.isValid) {
                    val doc = file.contentsToByteArray().toString(Charsets.UTF_8)
                    val start = chunk.startOffset.coerceIn(0, doc.length)
                    val end = chunk.endOffset.coerceIn(start, doc.length)
                    doc.substring(start, end)
                } else {
                    chunk.signature
                }
                RetrievedChunk(chunk, text)
            }
        }
    }

    private fun buildClient(provider: LlmProvider): Pair<LLMClient, LLModel> = when (provider) {
        is LlmProvider.Anthropic -> AnthropicLLMClient(provider.apiKey) to LLModel(
            provider = KoogLLMProvider.Anthropic,
            id = provider.model,
            capabilities = listOf(LLMCapability.Temperature, LLMCapability.Completion),
        )
        is LlmProvider.OpenAI -> OpenAILLMClient(provider.apiKey) to LLModel(
            provider = KoogLLMProvider.OpenAI,
            id = provider.model,
            capabilities = listOf(LLMCapability.Temperature, LLMCapability.Completion),
        )
        is LlmProvider.Ollama -> OllamaClient(baseUrl = provider.endpoint) to LLModel(
            provider = KoogLLMProvider.Ollama,
            id = provider.model,
            capabilities = listOf(LLMCapability.Temperature, LLMCapability.Completion),
        )
    }

    private fun redact(message: String, provider: LlmProvider): String {
        val secret = when (provider) {
            is LlmProvider.Anthropic -> provider.apiKey
            is LlmProvider.OpenAI -> provider.apiKey
            is LlmProvider.Ollama -> null
        }
        val cleaned = if (!secret.isNullOrBlank()) message.replace(secret, "***") else message
        // Also scrub common authorization header echoes.
        return cleaned
            .replace(Regex("(?i)(authorization|x-api-key)\\s*[:=]\\s*\\S+"), "$1: ***")
            .replace(Regex("sk-[A-Za-z0-9_-]{10,}"), "sk-***")
    }
}
