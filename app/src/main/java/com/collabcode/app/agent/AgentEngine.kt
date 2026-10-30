package com.collabcode.app.agent

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.collabcode.app.terminal.TerminalEngine
import com.collabcode.app.model.Attachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val TEXT_EXTENSIONS = listOf(
    "txt", "md", "json", "yaml", "yml", "dart", "js", "ts", "py", "html", "css", "kt", "java",
    "xml", "gradle", "sh", "toml", "csv",
)

/** The whole agent: chats, the model loop, tool approvals, stop and export. */
class AgentEngine(app: Application) : AndroidViewModel(app) {
    private val cfgStore = AgentConfigStore(app)
    private val chatStore = AgentStore(app)
    private val client = AgentClient()
    private val localClient = LocalClient(app)
    private val skills = SkillStore(app)
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var stopRequested = false

    var cfg by mutableStateOf(cfgStore.load())
        private set
    val chats = mutableStateListOf<Chat>()
    var cur by mutableStateOf<Chat?>(null)
        private set
    val files = mutableStateListOf<Attachment>()
    var busy by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)
    var settingsRequest by mutableStateOf(0)
    var skillList by mutableStateOf<List<Skill>>(emptyList())
        private set
    var models by mutableStateOf<List<Pair<String, Long>>>(emptyList())
        private set
    var dlName by mutableStateOf<String?>(null)
        private set
    var dlFraction by mutableStateOf<Float?>(null)
        private set
    private var dlJob: Job? = null

    init {
        BuiltinSkills.seedOnce(app)
        chats.addAll(chatStore.load())
        if (chats.isEmpty()) chats.add(Chat("New chat"))
        cur = chats.first()
        refreshSkills()
        refreshModels()
    }

    // ---------- chats ----------

    fun newChat() {
        if (busy) return
        val c = Chat("New chat")
        chats.add(0, c)
        cur = c
        persist()
    }

    fun select(c: Chat) {
        if (!busy) cur = c
    }

    fun deleteChat(c: Chat) {
        if (busy) return
        chats.remove(c)
        if (chats.isEmpty()) chats.add(Chat("New chat"))
        if (cur === c || cur == null) cur = chats.first()
        persist()
    }

    fun clearAll() {
        if (busy) return
        chats.clear()
        chats.add(Chat("New chat"))
        cur = chats.first()
        chatStore.clear()
    }

    private fun persist() {
        chatStore.save(chats.toList())
    }

    // ---------- settings ----------

    fun saveConfig(next: AgentConfig) {
        cfg = next
        cfgStore.save(next)
        client.resetTemperature()
        refreshModels()
        toast = "Settings saved"
    }

    fun setTheme(t: String) {
        cfg = cfg.copy(theme = t)
        cfgStore.save(cfg)
    }

    // ---------- skills ----------

    fun refreshSkills() {
        skillList = skills.all()
    }

    fun importSkills(uris: List<Uri>) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { skills.importUris(uris) }
            refreshSkills()
            toast = when {
                r.added.isNotEmpty() && r.problems.isEmpty() -> "Skill added: " + r.added.joinToString(", ")
                r.added.isNotEmpty() -> "Added " + r.added.size + ". Problem: " + r.problems.first()
                r.problems.isNotEmpty() -> r.problems.first()
                else -> "Nothing was added"
            }
        }
    }

    fun setSkillEnabled(s: Skill, on: Boolean) {
        skills.setEnabled(s.id, on)
        refreshSkills()
    }

    fun deleteSkill(s: Skill) {
        skills.delete(s.id)
        refreshSkills()
    }

    // ---------- on-device models ----------

    fun refreshModels() {
        models = LocalModels.list(getApplication()).map { it.name to it.length() }
        if (cfg.localModel.isBlank() && models.isNotEmpty()) {
            cfg = cfg.copy(localModel = models[0].first)
            cfgStore.save(cfg)
        }
    }

    fun downloadModel(m: CatalogModel) {
        if (dlJob?.isActive == true) return
        dlName = m.label
        dlFraction = null
        dlJob = viewModelScope.launch {
            try {
                LocalModels.download(getApplication(), m.url, m.file) { f, _ -> main.post { dlFraction = f } }
                toast = "Model ready: " + m.label
            } catch (e: CancellationException) {
                toast = "Download paused. Tap Download to continue."
                throw e
            } catch (e: Exception) {
                toast = e.message ?: "Download failed"
            } finally {
                dlName = null
                dlFraction = null
                refreshModels()
            }
        }
    }

    fun cancelDownload() {
        dlJob?.cancel()
    }

    fun importModel(uri: Uri) {
        if (dlJob?.isActive == true) return
        dlName = "Importing"
        dlFraction = null
        dlJob = viewModelScope.launch {
            try {
                LocalModels.import(getApplication(), uri) { f -> main.post { dlFraction = f } }
                toast = "Model imported"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = e.message ?: "Import failed"
            } finally {
                dlName = null
                dlFraction = null
                refreshModels()
            }
        }
    }

    fun deleteModel(name: String) {
        val app = getApplication<Application>()
        // unload() can wait for a reply that is still running, so it must not run on the UI thread.
        viewModelScope.launch(Dispatchers.IO) {
            LocalLlm.unload()
            LocalModels.delete(app, name)
            withContext(Dispatchers.Main) { refreshModels() }
        }
        if (cfg.localModel == name) {
            cfg = cfg.copy(localModel = "")
            cfgStore.save(cfg)
        }
    }

    fun useModel(name: String) {
        cfg = cfg.copy(localModel = name)
        cfgStore.save(cfg)
    }

    // ---------- attachments ----------

    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            for (u in uris) {
                val item = withContext(Dispatchers.IO) { readAttachment(u) } ?: continue
                files.add(item)
            }
        }
    }

    fun removeFile(i: Int) {
        if (i in files.indices) files.removeAt(i)
    }

    private fun readAttachment(uri: Uri): Attachment? {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = c.getString(ni) ?: name
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val isText = TEXT_EXTENSIONS.any { name.lowercase().endsWith(".$it") }
        val text = if (isText && size < 200_000) {
            resolver.openInputStream(uri)?.use { input -> input.bufferedReader().readText().take(200_000) }
        } else {
            null
        }
        val bytes = if (size >= 0) size else (text?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L)
        return Attachment(name, mime, bytes, text)
    }

    // ---------- sending ----------

    /** Returns true when the message was accepted. */
    fun send(raw: String): Boolean {
        val txt = raw.trim()
        if (txt.isEmpty() || busy) return false
        if (cfg.mode == MODE_CLOUD && cfg.key.isBlank()) {
            toast = "Add your API key in Settings, or switch to the on-device model"
            settingsRequest++
            return false
        }
        if (cfg.mode == MODE_DEVICE && LocalModels.list(getApplication()).isEmpty()) {
            toast = "Download or import a model first (menu > Models)"
            return false
        }
        val chat = cur ?: return false
        repair(chat)
        val at = files.joinToString("") { f ->
            val t = f.text
            if (t != null) "\n\n[File: ${f.name}]\n" + t.take(8000) else "\n\n[Attached: ${f.name} (${f.size} bytes)]"
        }
        files.clear()
        chat.ui.add(Me(txt))
        // A hint for the model only (not shown in the chat): which skills look close to this request.
        val near = skills.match(txt, 2).map { it.name }
        val hint = if (near.isEmpty()) "" else "\n\n[Hint: these skills may fit this request: " + near.joinToString(", ") + ". Read the closest one with read_skill before you work.]"
        chat.hist.add(JSONObject().put("role", "user").put("content", txt + at + hint))
        if (chat.title == "New chat") chat.title = txt.take(30)
        busy = true
        stopRequested = false
        persist()
        job = viewModelScope.launch {
            try {
                loop(chat)
            } catch (e: CancellationException) {
                chat.ui.add(Note("Stopped."))
                throw e
            } catch (e: Exception) {
                if (stopRequested) {
                    chat.ui.add(Note("Stopped."))
                } else {
                    chat.ui.add(Ai("Request failed: " + (e.message ?: e.toString())))
                }
            } finally {
                repair(chat)
                busy = false
                job = null
                persist()
            }
        }
        return true
    }

    private suspend fun loop(chat: Chat) {
        val device = cfg.mode == MODE_DEVICE
        for (step in 0 until 16) {
            val bubble = Ai("")
            chat.ui.add(bubble)
            val tools = AgentToolDefs.build(cfg)
            val system = AgentPrompt.system(cfg, skills, tools, device)
            val show: (String) -> Unit = { raw ->
                main.post {
                    val parts = ThinkSplit.split(raw)
                    bubble.think = parts.first
                    bubble.text = parts.second
                }
            }
            val res = if (device) {
                localClient.complete(cfg, system, chat.hist, show)
            } else {
                client.complete(cfg, system, chat.hist, tools, show)
            }
            val parts = ThinkSplit.split(res.text)
            bubble.think = parts.first
            bubble.text = parts.second
            if (bubble.text.isBlank() && bubble.think.isBlank()) chat.ui.remove(bubble)

            val m = JSONObject().put("role", "assistant").put("content", if (bubble.text.isEmpty()) JSONObject.NULL else bubble.text)
            if (res.calls.isNotEmpty()) {
                val arr = JSONArray()
                for (c in res.calls) {
                    arr.put(
                        JSONObject()
                            .put("id", c.id)
                            .put("type", "function")
                            .put("function", JSONObject().put("name", c.name).put("arguments", c.args))
                    )
                }
                m.put("tool_calls", arr)
            }
            chat.hist.add(m)
            if (res.calls.isEmpty()) break
            for (c in res.calls) {
                val result = runCall(c, chat)
                chat.hist.add(JSONObject().put("role", "tool").put("tool_call_id", c.id).put("content", result))
            }
            persist()
        }
    }

    private suspend fun runCall(c: ToolCallOut, chat: Chat): String {
        val a = try {
            JSONObject(c.args.ifBlank { "{}" })
        } catch (e: Exception) {
            JSONObject()
        }
        val card = ToolCard(c.name, a.toString().take(160))
        chat.ui.add(ToolItem(card))
        card.status = "Running"
        card.kind = "warn"
        return try {
            val out = AgentTools(cfg, getApplication(), skills).run(c.name, a, card) { cd, need -> ask(cd, need) }
            if (card.status != "Denied") {
                card.status = "Done"
                card.kind = "ok"
            }
            out
        } catch (e: CancellationException) {
            card.status = "Stopped"
            card.kind = "err"
            card.pending = false
            throw e
        } catch (e: Exception) {
            card.out = "Error: " + (e.message ?: e.toString())
            card.status = "Failed"
            card.kind = "err"
            card.out
        }
    }

    /** Shows the approval buttons and waits for the user. Without approval the call runs at once. */
    private suspend fun ask(card: ToolCard, need: Boolean): Boolean {
        if (!need) {
            card.status = "Auto-approved"
            card.kind = "ok"
            return true
        }
        val d = CompletableDeferred<Boolean>()
        card.decision = d
        card.pending = true
        card.status = "Needs approval"
        card.kind = "warn"
        try {
            val yes = d.await()
            card.pending = false
            card.decision = null
            card.status = if (yes) "Allowed" else "Denied"
            card.kind = if (yes) "ok" else "err"
            return yes
        } catch (e: CancellationException) {
            card.pending = false
            card.decision = null
            card.status = "Stopped"
            card.kind = "err"
            throw e
        }
    }

    fun decide(card: ToolCard, yes: Boolean) {
        card.decision?.complete(yes)
    }

    /** The stop control. Denies pending approvals, cancels the reply and stops the request. */
    fun stop() {
        if (!busy) return
        stopRequested = true
        for (ch in chats) {
            for (u in ch.ui) {
                if (u is ToolItem && u.card.pending) u.card.decision?.complete(false)
            }
        }
        client.cancelRequest()
        localClient.cancel()
        job?.cancel()
    }

    // ---------- history repair (a tool call always needs an answer) ----------

    private fun repair(chat: Chat) {
        val h = chat.hist
        var i = 0
        while (i < h.size) {
            val m = h[i]
            val calls = m.optJSONArray("tool_calls")
            if (m.optString("role") == "assistant" && calls != null) {
                val have = HashSet<String>()
                var j = i + 1
                while (j < h.size && h[j].optString("role") == "tool") {
                    have.add(h[j].optString("tool_call_id"))
                    j++
                }
                val add = mutableListOf<JSONObject>()
                for (k in 0 until calls.length()) {
                    val id = calls.getJSONObject(k).optString("id")
                    if (id !in have) {
                        add.add(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", "Cancelled before it ran."))
                    }
                }
                h.addAll(j, add)
                i = j + add.size
            } else {
                i++
            }
        }
    }

    // ---------- Run button on code blocks ----------

    class SnippetResult(val output: String, val ok: Boolean)

    /** Runs a code block from the chat in the real terminal. The user pressed Run, so no extra question. */
    suspend fun runSnippet(lang: String, code: String): SnippetResult {
        val app = getApplication<Application>()
        val (ext, runner) = when (lang.lowercase()) {
            "bash", "sh", "shell", "zsh", "console" -> "sh" to "bash"
            "python", "py", "python3" -> "py" to "python3"
            "javascript", "js", "node" -> "js" to "node"
            else -> return SnippetResult("Run is not available for \"$lang\".", false)
        }
        if (!TerminalEngine.isInstalled(app)) {
            return SnippetResult("The terminal is not set up yet. Open Terminal from the menu once, then press Run again.", false)
        }
        val dir = File(app.filesDir, "project/.run")
        val name = "run_" + System.currentTimeMillis() + "." + ext
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            File(dir, name).writeText(code)
        }
        return try {
            val r = TerminalEngine.exec(app, "cd /root/project && $runner .run/$name", 300)
            val text = r.output.trim().ifEmpty { "(no output)" }
            SnippetResult(text + "\n[" + (if (r.timedOut) "stopped: time limit" else "exit " + r.exit) + "]", !r.timedOut && r.exit == 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SnippetResult("Could not run: " + (e.message ?: e.toString()), false)
        }
    }

    // ---------- export ----------

    fun exportFile(): File? {
        val chat = cur ?: return null
        val text = chat.ui.joinToString("\n\n") { u ->
            when (u) {
                is Me -> "## You\n" + u.text
                is Ai -> "## CollabAgent\n" + u.text
                is Note -> "## CollabAgent\n" + u.text
                is ToolItem -> ""
            }
        }
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val name = chat.title.replace(Regex("\\W+"), "-").ifEmpty { "chat" }
        val f = File(dir, "$name.md")
        f.writeText(text)
        return f
    }
}
