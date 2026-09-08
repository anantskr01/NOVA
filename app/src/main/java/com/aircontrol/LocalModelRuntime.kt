package com.aircontrol

import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small adapter around the bundled llama.cpp Android AAR.
 * NovaAiClient remains the gateway; this class only owns local GGUF inference.
 */
class LocalModelRuntime(context: Context) {
    interface Callback {
        fun onResult(text: String)
        fun onError(message: String)
    }

    companion object {
        private const val MODEL_NAME = "nova.gguf"
        private const val CONTEXT_SIZE = 2048
        private const val THREADS = 4
        private const val MAX_TOKENS = 384
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var model: LlamaModel? = null
    private var loading = false

    fun isModelInstalled(): Boolean = modelFile().isFile && modelFile().length() > 0L

    fun tryChat(messages: JSONArray, callback: Callback): Boolean {
        if (!isModelInstalled()) return false

        scope.launch {
            try {
                val loaded = getOrLoadModel()
                val system = buildSystemPrompt(messages)
                val prompt = buildConversationPrompt(messages)
                val result = Llama.complete(
                    loaded,
                    prompt = prompt,
                    systemPrompt = system,
                    maxTokens = MAX_TOKENS,
                )
                val text = result.text.trim()
                if (text.isEmpty()) throw IllegalStateException("Local model returned no text")
                callback.onResult(text)
            } catch (t: Throwable) {
                callback.onError(t.message ?: "Local model inference failed")
            }
        }
        return true
    }

    fun shutdown() {
        scope.coroutineContext.cancel()
        synchronized(lock) {
            model?.let { Llama.releaseModel(it) }
            model = null
        }
    }

    private suspend fun getOrLoadModel(): LlamaModel {
        synchronized(lock) {
            model?.let { return it }
            if (loading) {
                // The caller that reaches here first owns loading. Others wait below.
            } else {
                loading = true
            }
        }

        synchronized(lock) {
            model?.let { return it }
        }

        return try {
            val loaded = Llama.loadModel(
                modelFile().absolutePath,
                LlamaConfig(
                    contextSize = CONTEXT_SIZE,
                    threads = THREADS,
                    gpuLayers = 0,
                    temperature = 0.2f,
                    topP = 0.9f,
                    topK = 40,
                ),
            )
            synchronized(lock) {
                model = loaded
                loading = false
            }
            loaded
        } catch (t: Throwable) {
            synchronized(lock) { loading = false }
            throw t
        }
    }

    private fun modelFile(): File = File(
        appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models"),
        MODEL_NAME,
    )

    private fun buildSystemPrompt(messages: JSONArray): String {
        val parts = ArrayList<String>()
        for (i in 0 until messages.length()) {
            val item = messages.optJSONObject(i) ?: continue
            if ("system" == item.optString("role")) {
                val content = item.optString("content", "").trim()
                if (content.isNotEmpty()) parts.add(content)
            }
        }
        return parts.joinToString("\n\n")
    }

    private fun buildConversationPrompt(messages: JSONArray): String {
        val out = StringBuilder()
        for (i in 0 until messages.length()) {
            val item: JSONObject = messages.optJSONObject(i) ?: continue
            val role = item.optString("role", "user").trim()
            if (role == "system") continue
            val content = item.optString("content", "").trim()
            if (content.isEmpty()) continue
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(role.uppercase()).append(": ").append(content)
        }
        return out.toString()
    }
}
