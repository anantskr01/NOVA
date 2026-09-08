package com.aircontrol

import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small adapter around the bundled llama.cpp Android AAR.
 * NovaAiClient remains the gateway; this class owns local GGUF installation and inference.
 */
class LocalModelRuntime {
    interface Callback {
        fun onResult(text: String)
        fun onError(message: String)
    }

    companion object {
        private const val CONTEXT_SIZE = 2048
        private const val THREADS = 4
        private const val MAX_TOKENS = 384
    }

    private val appContext: Context by lazy { resolveApplicationContext() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var model: LlamaModel? = null

    fun isModelInstalled(): Boolean = modelFile().isFile && modelFile().length() > 0L

    /**
     * Starts local inference. If the model is missing, the first call downloads it once
     * and then continues with inference. Returning true means the local path owns the callback.
     */
    fun tryChat(messages: JSONArray, callback: Callback): Boolean {
        scope.launch {
            try {
                val manager = LocalModelManager(appContext)
                if (!manager.isInstalled()) {
                    manager.install()
                }
                val loaded = getOrLoadModel()
                val result = Llama.complete(
                    loaded,
                    prompt = buildConversationPrompt(messages),
                    systemPrompt = buildSystemPrompt(messages),
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
        scope.cancel()
        synchronized(lock) {
            model?.let { Llama.releaseModel(it) }
            model = null
        }
    }

    private suspend fun getOrLoadModel(): LlamaModel {
        synchronized(lock) {
            model?.let { return it }
        }

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
            model?.let {
                Llama.releaseModel(loaded)
                return it
            }
            model = loaded
            return loaded
        }
    }

    private fun modelFile(): File = File(
        appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models"),
        LocalModelManager.MODEL_NAME,
    )

    private fun resolveApplicationContext(): Context {
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val method = activityThread.getDeclaredMethod("currentApplication")
            val application = method.invoke(null) as? android.app.Application
            if (application != null) return application.applicationContext
        } catch (_: Throwable) {
            // Fall through to a clear failure when the Android process is not initialized.
        }
        throw IllegalStateException("Android application context is not available")
    }

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
