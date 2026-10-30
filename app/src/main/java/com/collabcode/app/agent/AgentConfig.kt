package com.collabcode.app.agent

import android.content.Context
import com.collabcode.app.data.Secrets

const val MODE_CLOUD = "cloud"
const val MODE_DEVICE = "device"

/** How the agent behaves. The user can edit it in Settings. */
const val AGENT_DEFAULT_SYS =
    "You are CollabAgent, a helpful AI agent that lives on the user's Android phone. Behave like a normal, friendly assistant. " +
        "You have a real Linux terminal (run_command), web search (web_search, web_fetch) and skills the user uploaded (list_skills, read_skill).\n" +
        "Rules:\n" +
        "1) Do what the user asks and nothing more. If the request is unclear, ask ONE short question, or give 2 or 3 ideas and let the user choose.\n" +
        "2) For normal talk and simple questions, answer in chat. Do not use tools for that.\n" +
        "3) For a bigger task (build, code, design, research), first write a short plan of 2 to 5 numbered lines, then start working.\n" +
        "4) Before you write code or a design, look at the skills list. If one skill matches the topic, call read_skill for that ONE skill (the closest), " +
        "say which skill you read, then follow it. If no skill fits, go on without one. Use web_search when you need fresh facts or ideas.\n" +
        "5) The terminal is real. Nothing is simulated. Get tools with pip (PIP_BREAK_SYSTEM_PACKAGES=1 pip install NAME). " +
        "If a tool is not on pip, download it with wget. Create files with cat > path <<'EOF' or with echo. " +
        "Project files live in /root/project. Use the system package manager only as the last choice. " +
        "Check your work with cat, ls and by running the code.\n" +
        "6) Building an APK is one of your skills, not your only job. When the user wants an app as an APK: GitHub is free. " +
        "Read the skill apk-build-github, ask the user once for a GitHub token (API key), then use git, ssh or curl in the terminal, " +
        "let GitHub Actions build the APK, download it and hand it over with deliver_file.\n" +
        "7) Never fix a build or test error on your own unless the user asks. Report the error and ask.\n" +
        "8) Put code in fenced code blocks with a language tag so the user can copy and run it. Use ```html for web pages.\n" +
        "9) Never invent results. Report what the tools really returned.\n" +
        "Reply in the language the user writes in."

/** Every setting. Keys are encrypted on the device. */
data class AgentConfig(
    val mode: String = MODE_CLOUD,
    val key: String = "",
    val base: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o",
    val temp: Double = 0.4,
    val maxTok: Int = 2048,
    val sys: String = AGENT_DEFAULT_SYS,
    // sampling (used by the on-device model; top_p is also sent to the cloud)
    val topP: Double = 0.9,
    val topK: Int = 40,
    val repeatPenalty: Double = 1.1,
    val presencePenalty: Double = 0.0,
    val frequencyPenalty: Double = 0.0,
    val seed: Int = -1,
    // on-device model (llama.cpp)
    val localModel: String = "",
    val nCtx: Int = 4096,
    val threads: Int = defaultThreads(),
    // tools
    val webSearch: Boolean = true,
    val askT: Boolean = true,
    val askH: Boolean = true,
    // look
    val fs: Int = 15,
    val theme: String = "system",
    val showThink: Boolean = true,
)

fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)

class AgentConfigStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("agent_cfg", Context.MODE_PRIVATE)

    private fun secret(name: String): String =
        sp.getString(name, null)?.let { runCatching { Secrets.decrypt(it) }.getOrNull() }.orEmpty()

    private fun putSecret(name: String, value: String) {
        sp.edit().putString(name, if (value.isEmpty()) "" else Secrets.encrypt(value)).apply()
    }

    fun load(): AgentConfig {
        val d = AgentConfig()
        return AgentConfig(
            mode = sp.getString("mode", d.mode) ?: d.mode,
            key = secret("key"),
            base = sp.getString("base", d.base) ?: d.base,
            model = sp.getString("model", d.model) ?: d.model,
            temp = sp.getFloat("temp", d.temp.toFloat()).toDouble(),
            maxTok = sp.getInt("maxTok", d.maxTok),
            // Older versions saved their own prompt. Use the current one unless the user edited it.
            sys = sp.getString("sys_v2", d.sys) ?: d.sys,
            topP = sp.getFloat("topP", d.topP.toFloat()).toDouble(),
            topK = sp.getInt("topK", d.topK),
            repeatPenalty = sp.getFloat("repeatPenalty", d.repeatPenalty.toFloat()).toDouble(),
            presencePenalty = sp.getFloat("presencePenalty", d.presencePenalty.toFloat()).toDouble(),
            frequencyPenalty = sp.getFloat("frequencyPenalty", d.frequencyPenalty.toFloat()).toDouble(),
            seed = sp.getInt("seed", d.seed),
            localModel = sp.getString("localModel", d.localModel) ?: d.localModel,
            nCtx = sp.getInt("nCtx", d.nCtx),
            threads = sp.getInt("threads", d.threads),
            webSearch = sp.getBoolean("webSearch", d.webSearch),
            askT = sp.getBoolean("askT", d.askT),
            askH = sp.getBoolean("askH", d.askH),
            fs = sp.getInt("fs", d.fs),
            theme = sp.getString("theme", d.theme) ?: d.theme,
            showThink = sp.getBoolean("showThink", d.showThink),
        )
    }

    fun save(c: AgentConfig) {
        putSecret("key", c.key)
        sp.edit()
            .putString("mode", c.mode)
            .putString("base", c.base)
            .putString("model", c.model)
            .putFloat("temp", c.temp.toFloat())
            .putInt("maxTok", c.maxTok)
            .putString("sys_v2", c.sys)
            .putFloat("topP", c.topP.toFloat())
            .putInt("topK", c.topK)
            .putFloat("repeatPenalty", c.repeatPenalty.toFloat())
            .putFloat("presencePenalty", c.presencePenalty.toFloat())
            .putFloat("frequencyPenalty", c.frequencyPenalty.toFloat())
            .putInt("seed", c.seed)
            .putString("localModel", c.localModel)
            .putInt("nCtx", c.nCtx)
            .putInt("threads", c.threads)
            .putBoolean("webSearch", c.webSearch)
            .putBoolean("askT", c.askT)
            .putBoolean("askH", c.askH)
            .putInt("fs", c.fs)
            .putString("theme", c.theme)
            .putBoolean("showThink", c.showThink)
            .apply()
    }
}
