package com.collabcode.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ToolCallOut(val id: String, val name: String, val args: String)
class Completion(val text: String, val calls: List<ToolCallOut>)

/** Talks to any OpenAI-compatible chat endpoint with streaming and tools. */
class AgentClient {
    private val dropped = HashSet<String>()
    @Volatile private var conn: HttpURLConnection? = null

    fun cancelRequest() {
        conn?.disconnect()
    }

    fun resetTemperature() {
        dropped.clear()
    }

    suspend fun complete(
        cfg: AgentConfig,
        system: String,
        messages: List<JSONObject>,
        tools: JSONArray,
        onText: (String) -> Unit,
    ): Completion = withContext(Dispatchers.IO) { run(cfg, system, messages, tools, onText) }

    private class Acc {
        var id = ""
        var name = ""
        val args = StringBuilder()
    }

    private fun run(cfg: AgentConfig, system: String, messages: List<JSONObject>, tools: JSONArray, onText: (String) -> Unit): Completion {
        val url = URL(cfg.base.trimEnd('/') + "/chat/completions")
        val local = url.host == "localhost" || url.host == "127.0.0.1" || url.host == "10.0.2.2"
        if (!(url.protocol == "https" || (url.protocol == "http" && local))) {
            throw IOException("The model URL must use https.")
        }
        val sys = JSONObject().put("role", "system").put("content", system)
        val all = JSONArray().put(sys)
        messages.forEach { all.put(it) }
        val body = JSONObject()
            .put("model", cfg.model)
            .put("stream", true)
            .put("max_tokens", cfg.maxTok)
            .put("messages", all)
        if (tools.length() > 0) body.put("tools", tools)
        if ("temperature" !in dropped) body.put("temperature", cfg.temp)
        if ("top_p" !in dropped && cfg.topP < 1.0) body.put("top_p", cfg.topP)

        val c = url.openConnection() as HttpURLConnection
        conn = c
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer " + cfg.key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code == 400) {
                val t = errorText(c)
                for (p in listOf("temperature", "top_p")) {
                    if (p !in dropped && t.contains(p, ignoreCase = true)) {
                        dropped.add(p)
                        c.disconnect()
                        return run(cfg, system, messages, tools, onText)
                    }
                }
                throw IOException("API 400: " + t.take(300))
            }
            if (code !in 200..299) throw IOException("API $code: " + errorText(c).take(300))
            val ct = c.contentType ?: ""
            return if (ct.contains("event-stream")) stream(c, onText) else plain(c)
        } finally {
            c.disconnect()
        }
    }

    private fun errorText(c: HttpURLConnection): String =
        try {
            c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (e: IOException) {
            ""
        }

    private fun stream(c: HttpURLConnection, onText: (String) -> Unit): Completion {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<Acc>()
        c.inputStream.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val d = line.substring(5).trim()
                if (d.isEmpty() || d == "[DONE]") continue
                val j = try {
                    JSONObject(d)
                } catch (e: Exception) {
                    continue
                }
                val delta = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                // Providers send the reasoning as reasoning_content or reasoning.
                val rs = when {
                    !delta.isNull("reasoning_content") -> delta.optString("reasoning_content")
                    !delta.isNull("reasoning") -> delta.optString("reasoning")
                    else -> ""
                }
                if (rs.isNotEmpty()) {
                    reasoning.append(rs)
                    onText(compose(reasoning, text))
                }
                if (!delta.isNull("content")) {
                    val piece = delta.optString("content")
                    if (piece.isNotEmpty()) {
                        text.append(piece)
                        onText(compose(reasoning, text))
                    }
                }
                val tcs = delta.optJSONArray("tool_calls") ?: continue
                for (k in 0 until tcs.length()) {
                    val t = tcs.getJSONObject(k)
                    val idx = t.optInt("index", calls.size - 1).coerceAtLeast(0)
                    while (calls.size <= idx) calls.add(Acc())
                    val acc = calls[idx]
                    if (t.has("id")) acc.id = t.optString("id")
                    val f = t.optJSONObject("function")
                    if (f != null) {
                        val nm = f.optString("name")
                        if (nm.isNotEmpty() && acc.name.isEmpty()) acc.name = nm
                        acc.args.append(f.optString("arguments"))
                    }
                }
            }
        }
        val out = calls.filter { it.name.isNotEmpty() }.mapIndexed { i, a ->
            ToolCallOut(if (a.id.isEmpty()) "call_${System.currentTimeMillis()}_$i" else a.id, a.name, a.args.toString())
        }
        return Completion(text.toString(), out)
    }

    /** Reasoning goes in <think> tags so the app can show it apart from the answer. */
    private fun compose(reasoning: StringBuilder, text: StringBuilder): String =
        if (reasoning.isEmpty()) text.toString()
        else "<think>" + reasoning + (if (text.isEmpty()) "" else "</think>" + text)

    private fun plain(c: HttpURLConnection): Completion {
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val m = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val answer = if (m.isNull("content")) "" else m.optString("content")
        val rs = if (!m.isNull("reasoning_content")) m.optString("reasoning_content") else ""
        val text = if (rs.isEmpty()) answer else "<think>$rs</think>$answer"
        val tcs = m.optJSONArray("tool_calls") ?: JSONArray()
        val out = mutableListOf<ToolCallOut>()
        for (i in 0 until tcs.length()) {
            val t = tcs.getJSONObject(i)
            val f = t.getJSONObject("function")
            out.add(ToolCallOut(t.optString("id").ifEmpty { "call_$i" }, f.optString("name"), f.optString("arguments")))
        }
        return Completion(text, out.filter { it.name.isNotEmpty() })
    }
}
