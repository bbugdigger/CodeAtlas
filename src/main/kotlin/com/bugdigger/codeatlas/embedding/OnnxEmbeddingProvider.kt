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
 * Default provider for [com.bugdigger.codeatlas.index.CodeAtlasIndexService].
 *
 * Model loading order on first use:
 *  1. If a copy already lives at `<systemPath>/CodeAtlas/models/<MODEL_FILENAME>`, use it.
 *  2. Else if a bundled resource is present at [BUNDLED_MODEL_RESOURCE], copy it out
 *     to that path. This is the offline-first path for marketplace builds that ship
 *     the model in the plugin JAR.
 *  3. Else fall back to a one-time download from [MODEL_URL] over the public network.
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
            // DJL's Platform.detectPlatform locates its native-lib properties file via
            // Thread.currentThread().getContextClassLoader().getResources(...). Inside an
            // IntelliJ plugin the thread's context CL is the IDE's platform classloader,
            // which can't see resources packaged in this plugin's JAR — so the lookup
            // fails with "No tokenizers version found in property file." Pin the plugin
            // classloader for the duration of init and restore afterwards. ONNX Runtime
            // is wrapped in the same swap defensively.
            val originalCl = Thread.currentThread().contextClassLoader
            try {
                Thread.currentThread().contextClassLoader = OnnxEmbeddingProvider::class.java.classLoader
                if (tokenizer == null) {
                    // Pre-stage tokenizer.json on disk and load by Path. Avoids the native
                    // tokenizer's HF auto-download path, which fails inside the IntelliJ
                    // sandbox with "Bad URL: RelativeUrlWithoutBase".
                    ensureTokenizerFile()
                    tokenizer = HuggingFaceTokenizer.newInstance(modelDir)
                }
                if (session == null) {
                    val e = OrtEnvironment.getEnvironment()
                    env = e
                    session = e.createSession(ensureModelFile().toString(), OrtSession.SessionOptions())
                }
            } finally {
                Thread.currentThread().contextClassLoader = originalCl
            }
        }
    }

    private fun ensureModelFile(): Path =
        ensureFile(MODEL_FILENAME, BUNDLED_MODEL_RESOURCE, MODEL_URL)

    private fun ensureTokenizerFile(): Path =
        ensureFile(TOKENIZER_FILENAME, BUNDLED_TOKENIZER_RESOURCE, TOKENIZER_URL)

    private fun ensureFile(filename: String, bundledResource: String, downloadUrl: String): Path {
        val file = modelDir.resolve(filename)
        if (Files.exists(file)) return file
        Files.createDirectories(modelDir)
        val bundled = OnnxEmbeddingProvider::class.java.getResourceAsStream(bundledResource)
        if (bundled != null) {
            bundled.use { input ->
                Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING)
            }
            return file
        }
        URI(downloadUrl).toURL().openStream().use { input ->
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING)
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
        // INT8 quantized export from Xenova/bge-small-en-v1.5 — a community port that
        // publishes self-contained single-file ONNX models. The official BAAI repo's
        // model.onnx splits weights into model.onnx_data, which fails to load through
        // a single resource lookup. ~33 MB.
        private const val MODEL_URL =
            "https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/main/onnx/model_quantized.onnx"
        private const val MODEL_FILENAME = "bge-small-en-v1.5-int8.onnx"

        // Classpath path for the optionally-bundled model. When present, this path
        // wins over the network download. See src/main/resources/model/MODEL_CARD.md.
        private const val BUNDLED_MODEL_RESOURCE = "/model/bge-small-en-v1.5-int8.onnx"

        // DJL's HuggingFaceTokenizer.newInstance(Path) reads tokenizer.json from a directory.
        // We pre-stage this file so the native tokenizer never invokes its HTTP download path,
        // which fails inside the IntelliJ sandbox. Sourced from the same Xenova port for
        // consistency with [MODEL_URL] — the BERT-style tokenizer is identical across forks.
        private const val TOKENIZER_URL =
            "https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/main/tokenizer.json"
        private const val TOKENIZER_FILENAME = "tokenizer.json"
        private const val BUNDLED_TOKENIZER_RESOURCE = "/model/tokenizer.json"

        fun defaultModelDir(): Path =
            Paths.get(PathManager.getSystemPath(), "CodeAtlas", "models")
    }
}
