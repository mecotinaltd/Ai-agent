package com.collabcode.app.agent

import android.content.Context
import com.collabcode.app.terminal.TerminalEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AgentToolDefs {
    /** The tools the model may call. GitHub is not a tool: the agent uses git, ssh and curl in the terminal. */
    fun build(cfg: AgentConfig): JSONArray {
        val arr = JSONArray()
        arr.put(fn("list_skills", "List the skills the user uploaded (name and what each is for)."))
        arr.put(
            fn(
                "read_skill",
                "Read the full text of ONE skill. Do this before writing code or a design when a skill matches the topic.",
                "name" to "Skill name from list_skills",
                "file" to "(optional) a file inside the skill folder",
            )
        )
        arr.put(
            fn(
                "run_command",
                "Run a shell command in the real Linux terminal on the phone. Project folder: /root/project. " +
                    "Use pip, wget, cat, echo, ls, python3 and so on. Long jobs need timeout_sec.",
                "command" to "Shell command to run",
                "timeout_sec" to "(optional) seconds to wait, default 120, at most 900",
            )
        )
        if (cfg.webSearch) {
            arr.put(fn("web_search", "Search the web (free, no key). Returns titles, links and snippets.", "query" to "Search words"))
            arr.put(fn("web_fetch", "Open a web page and return its text.", "url" to "Full http or https address"))
        }
        arr.put(
            fn(
                "http_request",
                "Call any web API and return status and body (for example the GitHub API with a token the user gave).",
                "method" to "GET, POST, PUT, PATCH or DELETE",
                "url" to "Full https URL",
                "headers" to "(optional) JSON object of headers",
                "body" to "(optional) request body",
            )
        )
        arr.put(
            fn(
                "deliver_file",
                "Give a finished file (for example an APK) to the user. The user gets an Open/Install/Share button.",
                "path" to "File path inside /root/project",
            )
        )
        return arr
    }

    private fun fn(name: String, desc: String, vararg props: Pair<String, String>): JSONObject {
        val p = JSONObject()
        val req = JSONArray()
        for ((k, v) in props) {
            p.put(k, JSONObject().put("type", "string").put("description", v))
            if (!v.startsWith("(optional)")) req.put(k)
        }
        val params = JSONObject().put("type", "object").put("properties", p).put("required", req)
        return JSONObject()
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", desc).put("parameters", params))
    }
}

/** Runs the tools. Network calls run on the IO thread. Card updates stay on the caller's thread. */
class AgentTools(private val cfg: AgentConfig, private val ctx: Context, private val skills: SkillStore) {

    suspend fun run(name: String, a: JSONObject, card: ToolCard, ask: suspend (ToolCard, Boolean) -> Boolean): String =
        when (name) {
            "list_skills" -> listSkills(card)
            "read_skill" -> readSkill(a, card)
            "run_command" -> runCommand(a, card, ask)
            "web_search" -> if (cfg.webSearch) webSearch(a, card) else "Web search is turned off in Settings."
            "web_fetch" -> if (cfg.webSearch) webFetch(a, card) else "Web search is turned off in Settings."
            "http_request" -> httpRequest(a, card, ask)
            "deliver_file" -> deliverFile(a, card)
            else -> "Unknown tool: $name"
        }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun listSkills(card: ToolCard): String {
        val text = io { skills.catalog() }.ifEmpty { "No skills are installed. The user can add them with Skills > Upload." }
        card.out = text
        return text
    }

    private suspend fun readSkill(a: JSONObject, card: ToolCard): String {
        val name = a.optString("name")
        if (name.isBlank()) throw IOException("name is required")
        val file = a.optString("file").takeIf { it.isNotBlank() }
        val text = io {
            var t = skills.read(name, file, 14000)
            val s = skills.find(name)
            if (s != null && file == null) {
                val where = skills.syncToDebian(s)
                if (where != null) t += "\n\n[The skill's files are also in the terminal at $where]"
            }
            t
        }
        val found = skills.find(name)
        card.out = if (found != null) "Skill read: " + found.name else text
        return text
    }

    private suspend fun webSearch(a: JSONObject, card: ToolCard): String {
        val q = a.optString("query")
        card.out = "Searching: $q"
        val r = io { WebTools.search(q) }
        card.out = r.take(2500)
        return r
    }

    private suspend fun webFetch(a: JSONObject, card: ToolCard): String {
        val url = a.optString("url")
        card.out = "Opening: $url"
        val r = io { WebTools.fetch(url) }
        card.out = r.take(2500)
        return r
    }

    private suspend fun runCommand(a: JSONObject, card: ToolCard, ask: suspend (ToolCard, Boolean) -> Boolean): String {
        val command = a.optString("command").trim()
        if (command.isEmpty()) throw IOException("command is required")
        val timeout = (a.optString("timeout_sec").toLongOrNull() ?: 120L).coerceIn(5L, 900L)
        card.out = "$ " + command
        if (!ask(card, cfg.askT)) return "The user denied this command. Ask what they want instead."
        if (!TerminalEngine.isInstalled(ctx)) {
            throw IOException("The terminal is not set up yet. Open Terminal once to set it up, then try again.")
        }
        val r = TerminalEngine.exec(ctx, "cd /root/project && " + command, timeout)
        val text = r.output.trim().ifEmpty { "(no output)" }
        val status = if (r.timedOut) "stopped: time limit" else "exit " + r.exit
        card.out = "$ " + command + "\n" + text + "\n[" + status + "]"
        return "Exit " + r.exit + (if (r.timedOut) " (timed out)" else "") + "\n" + text.takeLast(6000)
    }

    private suspend fun httpRequest(a: JSONObject, card: ToolCard, ask: suspend (ToolCard, Boolean) -> Boolean): String {
        val m = a.optString("method").ifEmpty { "GET" }.uppercase()
        val url = a.optString("url")
        val body = a.optString("body")
        card.out = m + " " + url + (if (body.isNotEmpty()) "\n\n" + body.take(1500) else "")
        if (m != "GET" && !ask(card, cfg.askH)) return "The user denied this API call."
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("Give a full http or https URL")
        val headers = a.optString("headers")
        val h = if (headers.isBlank()) JSONObject() else JSONObject(headers)
        val out = io {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                // HttpURLConnection has no PATCH, so PATCH goes as POST with an override header.
                val wire = if (m == "PATCH") "POST" else m
                c.requestMethod = wire
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                if (m == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                val keys = h.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    c.setRequestProperty(k, h.optString(k))
                }
                if (body.isNotEmpty()) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = try {
                    if (code in 200..299) c.inputStream else c.errorStream
                } catch (e: IOException) {
                    null
                }
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                "HTTP $code\n" + text.take(6000)
            } finally {
                c.disconnect()
            }
        }
        card.out = out.take(3000)
        return out
    }

    /** Copies a project file to a place the system share/installer can read, and shows a button on the card. */
    private suspend fun deliverFile(a: JSONObject, card: ToolCard): String {
        var p = a.optString("path").trim()
        if (p.isEmpty()) throw IOException("path is required")
        p = p.removePrefix("/root/project/").removePrefix("./").removePrefix("/")
        val outPath = io {
            val project = File(ctx.filesDir, "project").canonicalFile
            val src = File(project, p).canonicalFile
            if (!src.path.startsWith(project.path + File.separator) || !src.isFile) {
                throw IOException("File not found in /root/project: $p")
            }
            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
            val dest = File(dir, src.name)
            src.copyTo(dest, overwrite = true)
            dest.path
        }
        val f = File(outPath)
        card.file = outPath
        card.out = "Ready: " + f.name + " (" + (f.length() / 1024) + " KB). Tap the button below."
        return "The file " + f.name + " was handed to the user with a button. Tell them to tap it."
    }
}
