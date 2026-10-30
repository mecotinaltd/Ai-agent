package com.collabcode.app.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One tool call shown as a card. kind is ok, warn or err. */
class ToolCard(val title: String, val sub: String) {
    var out by mutableStateOf("")
    var status by mutableStateOf("Running")
    var kind by mutableStateOf("warn")
    var pending by mutableStateOf(false)
    var decision: CompletableDeferred<Boolean>? = null
    /** A file the agent handed over (deliver_file). Empty when there is none. */
    var file by mutableStateOf("")
}

sealed interface Ui
class Me(val text: String) : Ui
class Ai(text: String) : Ui {
    var text by mutableStateOf(text)
    /** The model's visible reasoning (chain of thought), shown in a collapsible block. */
    var think by mutableStateOf("")
}

/** Splits streamed text into reasoning (inside <think>...</think>) and the answer. */
object ThinkSplit {
    fun split(raw: String): Pair<String, String> {
        val open = raw.indexOf("<think>")
        if (open < 0) {
            val close = raw.indexOf("</think>")
            return if (close < 0) "" to raw.trimStart() else raw.substring(0, close).trim() to raw.substring(close + 8).trimStart()
        }
        val before = raw.substring(0, open)
        val rest = raw.substring(open + 7)
        val close = rest.indexOf("</think>")
        return if (close < 0) {
            rest.trim() to before.trimStart()
        } else {
            rest.substring(0, close).trim() to (before + rest.substring(close + 8)).trimStart()
        }
    }
}
class Note(val text: String) : Ui
class ToolItem(val card: ToolCard) : Ui

class Chat(title: String) {
    var title by mutableStateOf(title)
    val ui = mutableStateListOf<Ui>()
    /** Messages sent to the model (OpenAI format). Kept between turns. */
    val hist = mutableListOf<JSONObject>()
}

/** Saves chats on the phone. Only the last 30 chats are kept. */
class AgentStore(ctx: android.content.Context) {
    private val file = File(ctx.filesDir, "agent_chats.json")

    fun load(): MutableList<Chat> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            val out = mutableListOf<Chat>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val chat = Chat(o.optString("title", "New chat"))
                val u = o.optJSONArray("ui") ?: JSONArray()
                for (k in 0 until u.length()) {
                    val x = u.getJSONObject(k)
                    when (x.optString("r")) {
                        "me" -> chat.ui.add(Me(x.optString("t")))
                        "ai" -> chat.ui.add(Ai(x.optString("t")).also { it.think = x.optString("k") })
                        "note" -> chat.ui.add(Note(x.optString("t")))
                        "tool" -> {
                            val card = ToolCard(x.optString("title"), x.optString("sub"))
                            card.out = x.optString("out")
                            card.status = x.optString("status", "Done")
                            card.kind = x.optString("kind", "ok")
                            card.file = x.optString("file")
                            if (card.status == "Running" || card.status == "Needs approval") {
                                card.status = "Stopped"
                                card.kind = "err"
                            }
                            chat.ui.add(ToolItem(card))
                        }
                    }
                }
                val h = o.optJSONArray("hist") ?: JSONArray()
                for (k in 0 until h.length()) chat.hist.add(h.getJSONObject(k))
                out.add(chat)
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(chats: List<Chat>) {
        val arr = JSONArray()
        for (c in chats.take(30)) {
            val u = JSONArray()
            for (item in c.ui) {
                when (item) {
                    is Me -> u.put(JSONObject().put("r", "me").put("t", item.text))
                    is Ai -> u.put(JSONObject().put("r", "ai").put("t", item.text).put("k", item.think))
                    is Note -> u.put(JSONObject().put("r", "note").put("t", item.text))
                    is ToolItem -> u.put(
                        JSONObject()
                            .put("r", "tool")
                            .put("title", item.card.title)
                            .put("sub", item.card.sub)
                            .put("out", item.card.out)
                            .put("status", item.card.status)
                            .put("kind", item.card.kind)
                            .put("file", item.card.file)
                    )
                }
            }
            val h = JSONArray()
            c.hist.forEach { h.put(it) }
            arr.put(JSONObject().put("title", c.title).put("ui", u).put("hist", h))
        }
        file.writeText(arr.toString())
    }

    fun clear() {
        file.delete()
    }
}
