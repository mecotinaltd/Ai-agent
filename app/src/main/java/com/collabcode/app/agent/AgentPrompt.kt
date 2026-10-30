package com.collabcode.app.agent

import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the system message: the editable prompt, the skills list, and (for the local model) the tool format. */
object AgentPrompt {
    fun system(cfg: AgentConfig, skills: SkillStore, tools: JSONArray, local: Boolean): String {
        val sb = StringBuilder(cfg.sys.trim())
        val cat = skills.catalog()
        sb.append("\n\n[Skills the user uploaded]\n")
        sb.append(if (cat.isEmpty()) "(none yet)" else cat)
        sb.append("\n\n[Today] ").append(SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).format(Date()))
        if (local) {
            sb.append("\n\n[How to call a tool]\n")
            sb.append("To use a tool, reply with ONE block and then stop:\n")
            sb.append("<tool_call>\n{\"name\": \"TOOL_NAME\", \"arguments\": {\"ARG\": \"value\"}}\n</tool_call>\n")
            sb.append("The result comes back inside <tool_response>. Then you may call the next tool, ")
            sb.append("or answer the user in plain text when you are done. Never invent tool results.\n")
            sb.append("Tools:\n").append(describe(tools))
        }
        return sb.toString()
    }

    private fun describe(tools: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until tools.length()) {
            val f = tools.getJSONObject(i).getJSONObject("function")
            val props = f.getJSONObject("parameters").getJSONObject("properties")
            val req = f.getJSONObject("parameters").getJSONArray("required")
            val required = HashSet<String>()
            for (k in 0 until req.length()) required.add(req.getString(k))
            val args = props.keys().asSequence().joinToString(", ") { if (it in required) it else "$it?" }
            sb.append("- ").append(f.getString("name")).append("(").append(args).append("): ")
                .append(f.getString("description")).append("\n")
        }
        return sb.toString()
    }
}
