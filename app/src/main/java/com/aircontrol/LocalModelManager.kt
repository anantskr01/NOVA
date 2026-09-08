package com.aircontrol

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads and atomically installs NOVA's bundled local GGUF model. */
class LocalModelManager(context: Context) {
    companion object {
        const val MODEL_NAME = "nova.gguf"
        const val MODEL_DISPLAY_NAME = "Qwen2.5 0.5B Instruct Q4_K_M"
        const val MODEL_SIZE_BYTES = 491_000_000L

        // Official Qwen GGUF repository; the runtime uses the Q4_K_M variant.
        private const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
    }

    private val appContext = context.applicationContext

    fun modelFile(): File = File(
        appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models"),
        MODEL_NAME,
    )

    fun isInstalled(): Boolean = modelFile().isFile && modelFile().length() > 0L

    suspend fun install(onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            val target = modelFile()
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, "$MODEL_NAME.part")

            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }

            try {
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("Model download failed (HTTP $code)")
                }

                val total = connection.contentLengthLong.takeIf { it > 0L } ?: MODEL_SIZE_BYTES
                var downloaded = 0L
                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(partial, false).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            onProgress(downloaded, total)
                        }
                        output.fd.sync()
                    }
                }

                if (partial.length() < 1_000_000L) {
                    throw IllegalStateException("Downloaded model is unexpectedly small")
                }

                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("Could not replace existing local model")
                }
                if (!partial.renameTo(target)) {
                    throw IllegalStateException("Could not finalize local model")
                }
                target
            } finally {
                connection.disconnect()
                if (partial.exists()) partial.delete()
            }
        }
}
