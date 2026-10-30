package com.collabcode.app.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Runs the on-device model (llama.cpp). Small models do not speak the API's tool format, so tools are
 * asked for in plain text: a <tool_call>{json}</tool_call> block. The history is converted to chat roles.
 */
class LocalClient(private val ctx: Context) {
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        LocalLlm.stop()
    }

    suspend fun complete(
        cfg: AgentConfig,
        system: String,
        messages: List<JSONObject>,
        onText: (String) -> Unit,
    ): Completion = withContext(Dispatchers.IO) {
        cancelled = false
        LocalLlm.ensureLoaded(ctx, cfg)?.let { throw IOException(it) }
        val maxNew = cfg.maxTok.coerceIn(64, cfg.nCtx / 2)
        // About 3 characters per token. Leave room for the answer.
        var budget = (cfg.nCtx - maxNew - 200) * 3
        var attempt = 0
        var result: Completion? = null
        while (result == null) {
            val chat = toChat(system, messages, budget)
            val acc = StringBuilder()
            val sink = PieceSink { bytes ->
                acc.append(String(bytes, Charsets.UTF_8))
                onText(visible(acc.toString()))
                !cancelled && !acc.contains("</tool_call>")
            }
            val code = LocalLlm.generate(cfg, chat.first, chat.second, maxNew, sink)
            if (code == -2 && attempt < 3) {
                budget = budget * 2 / 3
                attempt++
                continue
            }
            if (code < 0) throw IOException(errorText(code))
            result = parse(acc.toString())
        }
        result
    }

    private fun errorText(code: Int): String = when (code) {
        -1 -> "The model's chat template could not be applied."
        -2 -> "This chat is too long for the model's memory. Start a new chat or raise Context size in Settings."
        -3 -> "Not enough memory to run the model. Close other apps, or pick a smaller model."
        -4 -> "The model stopped while thinking (decode error)."
        -5 -> "The text could not be tokenized."
        else -> "Model error $code."
    }

    /** What the user sees while it streams: the text before a tool call. */
    private fun visible(s: String): String {
        val i = s.indexOf("<tool_call>")
        return (if (i >= 0) s.substring(0, i) else s).trimStart()
    }

    private fun toChat(system: String, messages: List<JSONObject>, budget: Int): Pair<List<String>, List<String>> {
        val roles = ArrayList<String>()
        val texts = ArrayList<String>()
        roles.add("system")
        texts.add(system)
        for (m in messages) {
            when (m.optString("role")) {
                "user" -> {
                    roles.add("user")
                    texts.add(m.optString("content"))
                }
                "assistant" -> {
                    val sb = StringBuilder()
                    if (!m.isNull("content")) sb.append(m.optString("content"))
                    val calls = m.optJSONArray("tool_calls")
                    if (calls != null) {
                        for (k in 0 until calls.length()) {
                            val f = calls.getJSONObject(k).getJSONObject("function")
                            val args: Any = try {
                                JSONObject(f.optString("arguments").ifBlank { "{}" })
                            } catch (e: Exception) {
                                JSONObject()
                            }
                            val call = JSONObject().put("name", f.optString("name")).put("arguments", args)
                            sb.append("\n<tool_call>\n").append(call.toString()).append("\n</tool_call>")
                        }
                    }
                    roles.add("assistant")
                    texts.add(sb.toString().trim())
                }
                "tool" -> {
                    roles.add("user")
                    texts.add("<tool_response>\n" + m.optString("content") + "\n</tool_response>")
                }
            }
        }
        // Fit the memory: shorten old tool output first, then drop the oldest turns.
        fun total() = texts.sumOf { it.length }
        var i = 1
        while (total() > budget && i < texts.size - 1) {
            val t = texts[i]
            if (t.startsWith("<tool_response>") && t.length > 1200) {
                texts[i] = t.take(700) + "\n...[shortened]...\n" + t.takeLast(400)
            }
            i++
        }
        while (total() > budget && texts.size > 3) {
            roles.removeAt(1)
            texts.removeAt(1)
        }
        return roles to texts
    }

    private fun parse(raw: String): Completion {
        val open = raw.indexOf("<tool_call>")
        if (open < 0) return Completion(raw.trim(), emptyList())
        val text = raw.substring(0, open).trim()
        val bodyStart = open + "<tool_call>".length
        val end = raw.indexOf("</tool_call>", bodyStart)
        val body = (if (end < 0) raw.substring(bodyStart) else raw.substring(bodyStart, end)).trim()
        val call = parseCall(body)
            ?: return Completion((text + "\n" + raw.substring(open)).trim(), emptyList())
        // One call at a time: the model sees the result before it picks the next step.
        return Completion(text, listOf(ToolCallOut("call_local_" + System.nanoTime(), call.first, call.second)))
    }

    private fun parseCall(body: String): Pair<String, String>? {
        val a = body.indexOf('{')
        val b = body.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try {
            val j = JSONObject(body.substring(a, b + 1))
            val name = j.optString("name")
            if (name.isEmpty()) return null
            val args = j.optJSONObject("arguments") ?: j.optJSONObject("parameters") ?: run {
                val s = j.optString("arguments")
                if (s.isNotBlank()) JSONObject(s) else JSONObject()
            }
            name to args.toString()
        } catch (e: Exception) {
            null
        }
    }
}
