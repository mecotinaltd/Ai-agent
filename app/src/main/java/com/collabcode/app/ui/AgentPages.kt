package com.collabcode.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.collabcode.app.agent.AGENT_DEFAULT_SYS
import com.collabcode.app.agent.AgentConfig
import com.collabcode.app.agent.AgentEngine
import com.collabcode.app.agent.LlamaBridge
import com.collabcode.app.agent.LocalModels
import com.collabcode.app.agent.MODE_CLOUD
import com.collabcode.app.agent.MODE_DEVICE
import com.collabcode.app.agent.defaultThreads

// ---------- Skills page ----------

@Composable
internal fun SkillsPage(engine: AgentEngine) {
    val pal = LocalAgent.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) engine.importSkills(uris)
    }
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Skills are instructions you upload (a SKILL.md file, or a .zip with skill folders). " +
                "Before the agent writes code or a design it reads the one skill that matches the topic.",
            color = pal.mu, fontSize = 13.sp,
        )
        AgentButton("Upload skill (.md or .zip)", variant = "pr", modifier = Modifier.fillMaxWidth()) { pick.launch("*/*") }
        if (engine.skillList.isEmpty()) {
            Text("No skills yet.", color = pal.mu, fontSize = 14.sp)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(engine.skillList, key = { it.id }) { s ->
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier.fillMaxWidth().clip(shape).border(1.dp, pal.bd, shape).background(pal.sf).padding(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Switch(
                            checked = s.enabled,
                            onCheckedChange = { engine.setSkillEnabled(s, it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = pal.ac),
                        )
                    }
                    Text(
                        s.description.ifEmpty { "(no description)" },
                        color = pal.mu, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextAction("Delete") { confirm = s.id }
                    }
                }
            }
        }
    }
    val target = engine.skillList.firstOrNull { it.id == confirm }
    if (target != null) {
        ConfirmDialog(
            title = "Delete skill?",
            message = "\"" + target.name + "\" is removed from this phone.",
            confirmLabel = "Delete",
            onConfirm = { engine.deleteSkill(target); confirm = null },
            onDismiss = { confirm = null },
        )
    }
}

// ---------- Models page (on-device llama.cpp) ----------

@Composable
internal fun ModelsPage(engine: AgentEngine) {
    val pal = LocalAgent.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) engine.importModel(uri)
    }
    var del by remember { mutableStateOf<String?>(null) }
    val busy = engine.dlName != null
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "The on-device model runs with llama.cpp inside this app. No API key and no internet are needed after the download. " +
                "Turn it on in Settings > Brain.",
            color = pal.mu, fontSize = 13.sp,
        )
        if (!LlamaBridge.available) {
            Text("The on-device engine is not part of this build.", color = pal.er, fontSize = 13.sp)
        } else if (!LocalModels.cpuSupportsBuild()) {
            Text("This phone's CPU may be too old for this build (it needs the dot-product instructions).", color = pal.wn, fontSize = 13.sp)
        }
        if (busy) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, pal.bd, RoundedCornerShape(16.dp))
                    .background(pal.sf).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(engine.dlName ?: "", fontWeight = FontWeight.SemiBold)
                val f = engine.dlFraction
                if (f != null) {
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = pal.ac)
                    Text((f * 100).toInt().toString() + "%", color = pal.mu, fontSize = 12.sp)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = pal.ac)
                }
                AgentButton("Stop", variant = "dn") { engine.cancelDownload() }
            }
        }
        Text("Download", fontWeight = FontWeight.SemiBold)
        for (m in LocalModels.CATALOG) {
            val have = engine.models.any { it.first == m.file }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, pal.bd, RoundedCornerShape(16.dp))
                    .background(pal.sf).padding(12.dp),
            ) {
                Text(m.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(m.note + " About " + m.sizeMb + " MB.", color = pal.mu, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                if (have) {
                    AgentPill("Installed", "ok")
                } else {
                    AgentButton("Download", variant = "pr", enabled = !busy) { engine.downloadModel(m) }
                }
            }
        }
        AgentButton("Import a .gguf file from the phone", modifier = Modifier.fillMaxWidth(), enabled = !busy) { pick.launch("*/*") }
        Text("On this phone", fontWeight = FontWeight.SemiBold)
        if (engine.models.isEmpty()) Text("No model yet.", color = pal.mu, fontSize = 13.sp)
        for ((name, size) in engine.models) {
            val inUse = engine.cfg.localModel == name
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, if (inUse) pal.ac else pal.bd, RoundedCornerShape(16.dp))
                    .background(pal.sf).padding(12.dp),
            ) {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text((size / 1_000_000).toString() + " MB", color = pal.mu, fontSize = 12.sp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (inUse) AgentPill("In use", "ok") else AgentButton("Use this") { engine.useModel(name) }
                    AgentButton("Delete", variant = "dn") { del = name }
                }
            }
        }
    }
    val d = del
    if (d != null) {
        ConfirmDialog(
            title = "Delete model?",
            message = "$d is removed from this phone. You can download it again.",
            confirmLabel = "Delete",
            onConfirm = { engine.deleteModel(d); del = null },
            onDismiss = { del = null },
        )
    }
}

// ---------- Settings ----------

@Composable
internal fun SettingsDialog(engine: AgentEngine, onClose: () -> Unit) {
    val pal = LocalAgent.current
    val c0 = engine.cfg
    var mode by remember { mutableStateOf(c0.mode) }
    var key by remember { mutableStateOf(c0.key) }
    var base by remember { mutableStateOf(c0.base) }
    var model by remember { mutableStateOf(c0.model) }
    var temp by remember { mutableStateOf(c0.temp.toString()) }
    var maxTok by remember { mutableStateOf(c0.maxTok.toString()) }
    var topP by remember { mutableStateOf(c0.topP.toString()) }
    var topK by remember { mutableStateOf(c0.topK.toString()) }
    var rep by remember { mutableStateOf(c0.repeatPenalty.toString()) }
    var pres by remember { mutableStateOf(c0.presencePenalty.toString()) }
    var freq by remember { mutableStateOf(c0.frequencyPenalty.toString()) }
    var seed by remember { mutableStateOf(c0.seed.toString()) }
    var nCtx by remember { mutableStateOf(c0.nCtx.toString()) }
    var threads by remember { mutableStateOf(c0.threads.toString()) }
    var sys by remember { mutableStateOf(c0.sys) }
    var web by remember { mutableStateOf(c0.webSearch) }
    var askT by remember { mutableStateOf(c0.askT) }
    var askH by remember { mutableStateOf(c0.askH) }
    var think by remember { mutableStateOf(c0.showThink) }
    var fs by remember { mutableStateOf(c0.fs) }
    var confirmClear by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = 680.dp)
                .clip(RoundedCornerShape(20.dp)).border(1.dp, pal.bd, RoundedCornerShape(20.dp))
                .background(pal.sf).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Settings", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            SectionLabel("Brain")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentButton("Cloud API", variant = if (mode == MODE_CLOUD) "pr" else "", modifier = Modifier.weight(1f)) { mode = MODE_CLOUD }
                AgentButton("On-device", variant = if (mode == MODE_DEVICE) "pr" else "", modifier = Modifier.weight(1f)) { mode = MODE_DEVICE }
            }
            if (mode == MODE_CLOUD) {
                SettingField("API key", key, password = true) { key = it }
                SettingField("Base URL (any OpenAI-compatible server)", base) { base = it }
                SettingField("Model ID", model) { model = it }
            } else {
                Text(
                    "Model: " + (c0.localModel.ifEmpty { "none yet. Open Models in the menu." }),
                    color = pal.mu, fontSize = 13.sp,
                )
                SettingField("Context size (tokens)", nCtx, number = true) { nCtx = it }
                SettingField("CPU threads", threads, number = true) { threads = it }
            }
            SectionLabel("Sampling")
            SettingField("Temperature (0 to 2)", temp, number = true) { temp = it }
            SettingField("Max output tokens", maxTok, number = true) { maxTok = it }
            SettingField("Top P (0 to 1)", topP, number = true) { topP = it }
            if (mode == MODE_DEVICE) {
                SettingField("Top K", topK, number = true) { topK = it }
                SettingField("Repeat penalty", rep, number = true) { rep = it }
                SettingField("Presence penalty", pres, number = true) { pres = it }
                SettingField("Frequency penalty", freq, number = true) { freq = it }
                SettingField("Seed (-1 = random)", seed, number = true) { seed = it }
            }
            SectionLabel("System prompt")
            SettingField("How the agent behaves", sys, lines = 6) { sys = it }
            AgentButton("Reset to default prompt", modifier = Modifier.fillMaxWidth()) { sys = AGENT_DEFAULT_SYS }
            SectionLabel("Tools")
            CheckRow("Web search and page reading", web) { web = it }
            CheckRow("Ask before terminal commands", askT) { askT = it }
            CheckRow("Ask before non-GET API calls", askH) { askH = it }
            SectionLabel("Appearance")
            CheckRow("Show the model's thinking", think) { think = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(14 to "Small", 15 to "Normal", 17 to "Large").forEach { (v, label) ->
                    AgentButton(label, variant = if (fs == v) "pr" else "", modifier = Modifier.weight(1f)) { fs = v }
                }
            }
            SectionLabel("Data")
            AgentButton("Delete all chats", variant = "dn", modifier = Modifier.fillMaxWidth()) { confirmClear = true }
            Text(
                "Keys are encrypted on this phone. They are sent only to the model URL you set.",
                fontSize = 12.sp, color = pal.mu,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                AgentButton("Cancel", onClick = onClose)
                Spacer(Modifier.width(8.dp))
                AgentButton("Save", variant = "pr") {
                    engine.saveConfig(
                        c0.copy(
                            mode = mode,
                            key = key.trim(),
                            base = base.trim().ifEmpty { "https://api.openai.com/v1" },
                            model = model.trim().ifEmpty { "gpt-4o" },
                            temp = temp.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 0.4,
                            maxTok = maxTok.toIntOrNull()?.takeIf { it > 0 } ?: 2048,
                            topP = topP.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.9,
                            topK = topK.toIntOrNull()?.coerceAtLeast(0) ?: 40,
                            repeatPenalty = rep.toDoubleOrNull()?.coerceIn(0.5, 2.0) ?: 1.1,
                            presencePenalty = pres.toDoubleOrNull()?.coerceIn(-2.0, 2.0) ?: 0.0,
                            frequencyPenalty = freq.toDoubleOrNull()?.coerceIn(-2.0, 2.0) ?: 0.0,
                            seed = seed.toIntOrNull() ?: -1,
                            nCtx = nCtx.toIntOrNull()?.coerceIn(512, 32768) ?: 4096,
                            threads = threads.toIntOrNull()?.coerceIn(1, 16) ?: defaultThreads(),
                            sys = sys.trim().ifEmpty { AGENT_DEFAULT_SYS },
                            webSearch = web,
                            askT = askT,
                            askH = askH,
                            showThink = think,
                            fs = fs,
                        )
                    )
                    onClose()
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Delete all chats?",
            message = "All chats on this phone are deleted.",
            confirmLabel = "Delete",
            onConfirm = { confirmClear = false; engine.clearAll(); onClose() },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    val pal = LocalAgent.current
    Text(text, Modifier.padding(top = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ac)
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    password: Boolean = false,
    number: Boolean = false,
    lines: Int = 1,
    onChange: (String) -> Unit,
) {
    val pal = LocalAgent.current
    val shape = RoundedCornerShape(12.dp)
    Column {
        Text(label, fontSize = 12.sp, color = pal.mu)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = lines == 1,
            minLines = lines,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    number -> KeyboardType.Decimal
                    password -> KeyboardType.Password
                    else -> KeyboardType.Text
                }
            ),
            textStyle = TextStyle(color = pal.tx, fontSize = 15.sp),
            cursorBrush = SolidColor(pal.ac),
            modifier = Modifier.fillMaxWidth().clip(shape).border(1.dp, pal.bd, shape).background(pal.bg)
                .padding(horizontal = 11.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalAgent.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = onChange, colors = CheckboxDefaults.colors(checkedColor = pal.ac))
    }
}
