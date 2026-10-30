package com.collabcode.app.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class CatalogModel(val label: String, val file: String, val url: String, val sizeMb: Int, val note: String)

/** The GGUF files on the phone (app storage), plus import and download. */
object LocalModels {
    val CATALOG = listOf(
        CatalogModel(
            "Qwen2.5 Coder 1.5B (recommended)",
            "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            986,
            "Good for code. Needs about 2 GB free RAM.",
        ),
        CatalogModel(
            "Qwen2.5 Coder 3B",
            "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            2100,
            "Smarter, slower. Needs about 4 GB free RAM.",
        ),
        CatalogModel(
            "Qwen2.5 0.5B (tiny)",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            400,
            "Fast on weak phones, but makes many mistakes.",
        ),
    )

    fun dir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }?.sortedBy { it.name } ?: emptyList()

    /** The file chosen in settings, or the only/first model when none is chosen. */
    fun resolve(ctx: Context, chosen: String): File? {
        val all = list(ctx)
        return all.firstOrNull { it.name == chosen } ?: all.firstOrNull()
    }

    fun delete(ctx: Context, name: String) {
        File(dir(ctx), name).delete()
        File(dir(ctx), "$name.part").delete()
    }

    /** True when this CPU has the dot-product instructions the native build expects. */
    fun cpuSupportsBuild(): Boolean =
        try {
            File("/proc/cpuinfo").readText().contains("asimddp") || !LlamaBridge.available
        } catch (e: Exception) {
            true
        }

    /** Copies a .gguf the user picked into app storage. */
    suspend fun import(ctx: Context, uri: Uri, onProgress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        var name = "model.gguf"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = c.getString(ni) ?: name
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (!name.endsWith(".gguf", ignoreCase = true)) throw IOException("Pick a .gguf model file.")
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (size > 0 && dir(ctx).usableSpace < size + 200_000_000L) throw IOException("Not enough free storage for this model.")
        val part = File(dir(ctx), "$name.part")
        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open the file.")
        input.use { i ->
            part.outputStream().use { o ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    onProgress(if (size > 0) done.toFloat() / size else null)
                }
            }
        }
        val out = File(dir(ctx), name)
        out.delete()
        if (!part.renameTo(out)) throw IOException("Could not save the model.")
        out
    }

    /** Downloads a model (https only), and continues a stopped download. */
    suspend fun download(ctx: Context, url: String, fileName: String, onProgress: (Float?, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val name = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "model.gguf" }
            if (!name.endsWith(".gguf", ignoreCase = true)) throw IOException("The file name must end with .gguf")
            val part = File(dir(ctx), "$name.part")
            var existing = if (part.exists()) part.length() else 0L
            var current = url
            for (hop in 0..5) {
                val u = URL(current)
                if (u.protocol != "https") throw IOException("Only https downloads are allowed.")
                val c = u.openConnection() as HttpURLConnection
                try {
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 20_000
                    c.readTimeout = 60_000
                    c.setRequestProperty("User-Agent", "CollabAgent/0.10")
                    if (existing > 0) c.setRequestProperty("Range", "bytes=$existing-")
                    val code = c.responseCode
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location") ?: throw IOException("The download redirect had no address.")
                        current = URL(u, loc).toString()
                        continue
                    }
                    if (code == 416) {
                        existing = 0L
                        part.delete()
                        continue
                    }
                    if (code != 200 && code != 206) throw IOException("Download failed (HTTP $code).")
                    val append = code == 206 && existing > 0
                    if (!append) existing = 0L
                    val remaining = c.contentLengthLong
                    val total = if (remaining > 0) existing + remaining else -1L
                    if (total > 0 && dir(ctx).usableSpace < (total - existing) + 200_000_000L) {
                        throw IOException("Not enough free storage. About ${total / 1_000_000} MB is needed.")
                    }
                    var done = existing
                    java.io.FileOutputStream(part, append).use { out ->
                        c.inputStream.use { input ->
                            val buf = ByteArray(128 * 1024)
                            var last = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - last > 400) {
                                    last = now
                                    onProgress(if (total > 0) done.toFloat() / total else null, done)
                                }
                            }
                        }
                    }
                    if (total > 0 && done < total) throw IOException("The download stopped early. Tap Download again to continue.")
                    val outFile = File(dir(ctx), name)
                    outFile.delete()
                    if (!part.renameTo(outFile)) throw IOException("Could not save the model.")
                    return@withContext outFile
                } finally {
                    c.disconnect()
                }
            }
            throw IOException("Too many redirects.")
        }
}

/** Keeps one model loaded in memory and runs requests on it. */
object LocalLlm {
    private var handle = 0L
    private var loadedKey = ""

    val isLoaded: Boolean get() = handle != 0L

    @Synchronized
    fun ensureLoaded(ctx: Context, cfg: AgentConfig): String? {
        if (!LlamaBridge.available) return "The on-device engine is not part of this build."
        val file = LocalModels.resolve(ctx, cfg.localModel) ?: return "No model on the phone yet. Open Models to download or import one."
        val key = "${file.path}|${cfg.nCtx}|${cfg.threads}"
        if (handle != 0L && key == loadedKey) return null
        if (handle != 0L) {
            LlamaBridge.nativeFree(handle)
            handle = 0L
        }
        val h = LlamaBridge.nativeLoad(file.path, cfg.nCtx, cfg.threads)
        if (h == 0L) return "The model could not be loaded. The file may be damaged, or the phone has too little free memory."
        handle = h
        loadedKey = key
        return null
    }

    fun stop() {
        val h = handle
        if (h != 0L) LlamaBridge.nativeStop(h)
    }

    @Synchronized
    fun unload() {
        if (handle != 0L) LlamaBridge.nativeFree(handle)
        handle = 0L
        loadedKey = ""
    }

    fun generate(cfg: AgentConfig, roles: List<String>, contents: List<String>, maxTokens: Int, sink: PieceSink): Int {
        val h = handle
        if (h == 0L) return -3
        return LlamaBridge.nativeGenerate(
            h, roles.toTypedArray(), contents.toTypedArray(), maxTokens,
            cfg.temp.toFloat(), cfg.topP.toFloat(), cfg.topK, cfg.repeatPenalty.toFloat(),
            cfg.presencePenalty.toFloat(), cfg.frequencyPenalty.toFloat(), cfg.seed, sink,
        )
    }
}
