package com.collabcode.app.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.collabcode.app.terminal.RootfsManager
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** One skill: a folder with a SKILL.md (name and description at the top) and optional extra files. */
class Skill(val id: String, val name: String, val description: String, val dir: File, val enabled: Boolean) {
    /** The main file of the skill. */
    val main: File get() = File(dir, "SKILL.md")
}

class SkillImport(val added: List<String>, val problems: List<String>)

/**
 * Skills the user uploads. The agent sees the list (name and description), reads the one that fits the
 * task before it works, and then follows it. Everything stays on the phone.
 */
class SkillStore(private val ctx: Context) {
    private val root = File(ctx.filesDir, "skills").apply { mkdirs() }
    private val stateFile = File(ctx.filesDir, "skills_state.json")

    private fun state(): JSONObject =
        try {
            if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }

    fun all(): List<Skill> {
        val st = state()
        val dirs = root.listFiles { f -> f.isDirectory && File(f, "SKILL.md").exists() }?.sortedBy { it.name } ?: return emptyList()
        return dirs.map { d ->
            val text = runCatching { File(d, "SKILL.md").readText() }.getOrDefault("")
            val (n, desc) = parseMeta(text, d.name)
            Skill(d.name, n, desc, d, st.optBoolean(d.name, true))
        }
    }

    fun setEnabled(id: String, on: Boolean) {
        val st = state()
        st.put(id, on)
        stateFile.writeText(st.toString())
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        val st = state()
        st.remove(id)
        stateFile.writeText(st.toString())
    }

    // ---------- reading ----------

    fun find(nameOrId: String): Skill? {
        val q = nameOrId.trim().lowercase()
        if (q.isEmpty()) return null
        val list = all().filter { it.enabled }
        return list.firstOrNull { it.id == slug(q) || it.name.lowercase() == q }
            ?: list.firstOrNull { it.name.lowercase().contains(q) || q.contains(it.name.lowercase()) }
    }

    /** The text the model gets. A [file] inside the skill folder can be asked for by its relative path. */
    fun read(nameOrId: String, file: String?, maxChars: Int): String {
        val s = find(nameOrId) ?: return "No skill named \"$nameOrId\". Call list_skills to see the names."
        if (!file.isNullOrBlank()) {
            val target = File(s.dir, file).canonicalFile
            if (!target.path.startsWith(s.dir.canonicalPath + File.separator) || !target.isFile) {
                return "File not found inside the skill: $file"
            }
            return target.readText().take(maxChars)
        }
        val body = s.main.readText()
        val others = s.dir.walkTopDown().filter { it.isFile && it.name != "SKILL.md" }
            .map { it.relativeTo(s.dir).path }.take(30).toList()
        val tail = if (others.isEmpty()) "" else
            "\n\n[Other files in this skill: " + others.joinToString(", ") +
                ". Read one with read_skill(name, file).]"
        return body.take(maxChars) + tail
    }

    /** Copies the skill into the terminal (/root/skills/ID) so its scripts can be run. Best effort. */
    fun syncToDebian(s: Skill): String? {
        return try {
            val rootfsHome = File(RootfsManager.rootfsDir(ctx), "root")
            if (!rootfsHome.isDirectory) return null
            val dest = File(rootfsHome, "skills/${s.id}")
            dest.deleteRecursively()
            s.dir.copyRecursively(dest, overwrite = true)
            "/root/skills/${s.id}"
        } catch (e: Exception) {
            null
        }
    }

    /** The list shown to the model on every request. */
    fun catalog(maxSkills: Int = 30, descLen: Int = 140): String {
        val list = all().filter { it.enabled }.take(maxSkills)
        if (list.isEmpty()) return ""
        return list.joinToString("\n") { "- ${it.name}: ${it.description.take(descLen)}" }
    }

    // ---------- matching ----------

    private val stop = setOf(
        "the", "and", "for", "you", "with", "that", "this", "from", "have", "can", "please", "are", "was",
        "your", "mujhe", "karo", "kar", "banao", "bana", "dena", "do", "hai", "hain", "aur", "wala", "wali",
        "mein", "main", "yeh", "woh", "ko", "ka", "ki", "ke", "se", "ek", "kya", "kaise", "chahiye",
    )

    private fun words(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }
            .filter { it.length >= 3 && it !in stop }.toSet()

    /** The closest skills to a request. Empty when nothing is close. */
    fun match(request: String, limit: Int): List<Skill> {
        val req = words(request)
        if (req.isEmpty()) return emptyList()
        val scored = all().filter { it.enabled }.mapNotNull { s ->
            val nameW = words(s.name.replace('-', ' ').replace('_', ' '))
            val descW = words(s.description)
            val nameHits = nameW.count { it in req }
            val descHits = descW.count { it in req }
            val score = nameHits * 2 + descHits
            if (score >= 2 || nameHits >= 1) s to score else null
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    // ---------- importing ----------

    fun importUris(uris: List<Uri>): SkillImport {
        val added = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val resolver = ctx.contentResolver
        for (uri in uris) {
            var fileName = uri.lastPathSegment ?: "skill"
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) fileName = c.getString(i) ?: fileName
                }
            }
            try {
                val input = resolver.openInputStream(uri) ?: throw IllegalStateException("cannot open the file")
                input.use {
                    if (fileName.lowercase().endsWith(".zip")) {
                        importZip(it, fileName.substringBeforeLast('.'), added, problems)
                    } else {
                        val text = it.bufferedReader().readText()
                        if (text.length > 400_000) throw IllegalStateException("the file is too large for a skill")
                        added.add(saveText(text, fileName.substringBeforeLast('.')))
                    }
                }
            } catch (e: Exception) {
                problems.add("$fileName: ${e.message ?: e.toString()}")
            }
        }
        return SkillImport(added, problems)
    }

    private fun saveText(text: String, fallbackName: String): String {
        val (name, _) = parseMeta(text, fallbackName)
        val dir = File(root, slug(name))
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(text)
        return name
    }

    private fun importZip(input: java.io.InputStream, zipName: String, added: MutableList<String>, problems: MutableList<String>) {
        val tmp = File(ctx.cacheDir, "skill_import_" + System.nanoTime()).apply { mkdirs() }
        try {
            val base = tmp.canonicalPath + File.separator
            var files = 0
            var bytes = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = File(tmp, e.name).canonicalFile
                    if (!out.path.startsWith(base)) continue // a path that leaves the folder is skipped
                    if (e.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    files++
                    if (files > 500) throw IllegalStateException("the zip has too many files")
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            bytes += n
                            if (bytes > 20_000_000L) throw IllegalStateException("the zip is larger than 20 MB unpacked")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
            val markers = tmp.walkTopDown().filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }.toList()
            if (markers.isEmpty()) {
                val md = tmp.walkTopDown().filter { it.isFile && it.extension.lowercase() in listOf("md", "txt") }.toList()
                if (md.size == 1) {
                    added.add(saveText(md[0].readText(), zipName))
                } else {
                    problems.add("$zipName: no SKILL.md found inside the zip")
                }
                return
            }
            for (m in markers) {
                val dir = m.parentFile ?: continue
                val text = m.readText()
                val (name, _) = parseMeta(text, if (dir == tmp) zipName else dir.name)
                val dest = File(root, slug(name))
                dest.deleteRecursively()
                dir.copyRecursively(dest, overwrite = true)
                val main = File(dest, m.name)
                if (m.name != "SKILL.md") main.renameTo(File(dest, "SKILL.md"))
                added.add(name)
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    companion object {
        fun slug(name: String): String {
            val s = name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(48)
            return s.ifEmpty { "skill" }
        }

        /** Reads name and description from the front matter. Without it: the file name and the first text line. */
        fun parseMeta(text: String, fallbackName: String): Pair<String, String> {
            var name = ""
            var desc = ""
            val lines = text.lines()
            if (lines.isNotEmpty() && lines[0].trim() == "---") {
                var i = 1
                while (i < lines.size && lines[i].trim() != "---") {
                    val l = lines[i]
                    val key = l.substringBefore(':', "").trim().lowercase()
                    val value = l.substringAfter(':', "").trim()
                    if (key == "name") name = value.trim('"', '\'')
                    if (key == "description") {
                        if (value == ">" || value == "|" || value == ">-" || value == "|-") {
                            val sb = StringBuilder()
                            var j = i + 1
                            while (j < lines.size && (lines[j].startsWith(" ") || lines[j].startsWith("\t"))) {
                                sb.append(lines[j].trim()).append(' ')
                                j++
                            }
                            desc = sb.toString().trim()
                            i = j - 1
                        } else {
                            desc = value.trim('"', '\'')
                        }
                    }
                    i++
                }
            }
            if (name.isBlank()) name = fallbackName.ifBlank { "skill" }
            if (desc.isBlank()) {
                desc = lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") && it.trim() != "---" && !it.contains(':') }
                    ?.trim().orEmpty()
                if (desc.isBlank()) desc = lines.firstOrNull { it.startsWith("#") }?.trimStart('#', ' ').orEmpty()
            }
            return name to desc.replace("\n", " ").take(300)
        }
    }
}
