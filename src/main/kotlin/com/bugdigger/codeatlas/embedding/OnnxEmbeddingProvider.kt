package com.bugdigger.codeatlas.embedding

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.intellij.openapi.application.PathManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.math.sqrt

/**
 * Real embedder backed by BAAI/bge-small-en-v1.5 (INT8-quantized ONNX export).
 *
 * Not the default provider in Phase 1 Week 2 — the model file is ~33MB and is
 * downloaded lazily from Hugging Face on first use. [HashEmbeddingProvider] is
 * wired in [com.bugdigger.codeatlas.index.CodeAtlasIndexService] until the
 * download/session path has been verified on a range of networks.
 *
 * Inputs: `input_ids`, `attention_mask`, `token_type_ids` (all int64 `[1, seq]`).
 * Output: `last_hidden_state` `[1, seq, 384]`; mean-pooled with the attention
 * mask and L2-normalized to produce a unit vector.
 */
class OnnxEmbeddingProvider(
    private val modelDir: Path = defaultModelDir(),
) : EmbeddingProvider {

    override val dim: Int = 384
    override val modelId: String = "BAAI/bge-small-en-v1.5-int8"

    private val lock = Any()

    @Volatile private var tokenizer: HuggingFaceTokenizer? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        initialize()
        val tok = tokenizer!!
        val sess = session!!
        val environment = env!!
        return withContext(Dispatchers.IO) {
            texts.map { embedOne(it, tok, sess, environment) }
        }
    }

    private fun initialize() {
        if (tokenizer != null && session != null) return
        synchronized(lock) {
            if (tokenizer == null) {
                tokenizer = HuggingFaceTokenizer.newInstance("BAAI/bge-small-en-v1.5")
            }
            if (session == null) {
                val e = OrtEnvironment.getEnvironment()
                env = e
                session = e.createSession(ensureModelFile().toString(), OrtSession.SessionOptions())
            }
        }
    }

    private fun ensureModelFile(): Path {
        val file = modelDir.resolve(MODEL_FILENAME)
        if (!Files.exists(file)) {
            Files.createDirectories(modelDir)
            URI(MODEL_URL).toURL().openStream().use { input ->
                Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return file
    }

    private fun embedOne(
        text: String,
        tok: HuggingFaceTokenizer,
        sess: OrtSession,
        env: OrtEnvironment,
    ): FloatArray {
        val encoding = tok.encode(text)
        val ids = arrayOf(encoding.ids)
        val mask = arrayOf(encoding.attentionMask)
        val typeIds = arrayOf(encoding.typeIds)

        OnnxTensor.createTensor(env, ids).use { idsTensor ->
            OnnxTensor.createTensor(env, mask).use { maskTensor ->
                OnnxTensor.createTensor(env, typeIds).use { typeTensor ->
                    val inputs = mapOf(
                        "input_ids" to idsTensor,
                        "attention_mask" to maskTensor,
                        "token_type_ids" to typeTensor,
                    )
                    sess.run(inputs).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = result[0].value as Array<Array<FloatArray>>
                        val pooled = meanPool(hidden[0], encoding.attentionMask)
                        return l2Normalize(pooled)
                    }
                }
            }
        }
    }

    private fun meanPool(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val d = hidden[0].size
        val pooled = FloatArray(d)
        var kept = 0
        for (t in hidden.indices) {
            if (t >= mask.size || mask[t] == 0L) continue
            val row = hidden[t]
            for (k in 0 until d) pooled[k] += row[k]
            kept++
        }
        if (kept > 0) {
            for (k in 0 until d) pooled[k] /= kept
        }
        return pooled
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x.toDouble() * x.toDouble()
        val n = sqrt(s).toFloat()
        if (n > 0f) for (i in v.indices) v[i] /= n
        return v
    }

    companion object {
        // INT8 quantized export from the official bge-small ONNX bundle on Hugging Face.
        private const val MODEL_URL =
            "https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/onnx/model_quantized.onnx"
        private const val MODEL_FILENAME = "bge-small-en-v1.5-int8.onnx"

        fun defaultModelDir(): Path =
            Paths.get(PathManager.getSystemPath(), "CodeAtlas", "models")
    }
}
