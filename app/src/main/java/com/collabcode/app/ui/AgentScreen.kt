package com.collabcode.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.collabcode.app.agent.AgentEngine
import com.collabcode.app.agent.MODE_DEVICE
import com.collabcode.app.terminal.TerminalEngine
import com.collabcode.app.terminal.TerminalSetup
import kotlinx.coroutines.launch

private val WELCOME_CHIPS = listOf(
    "Brainstorm an app idea with me" to "I want to build an app but I do not know what. Ask me 2 questions and give me 3 ideas.",
    "Find the right skill and start" to "Look at my skills list and tell me which skill fits a simple to-do app, then plan it.",
    "Install a tool with pip" to "Check python3 and pip in the terminal and show their versions.",
    "Build an APK for free" to "I want an Android app built as an APK using GitHub. Tell me what you need from me.",
)

/** The whole screen: sidebar, header, pages (chat, skills, models, terminal), composer and settings. */
@Composable
fun AgentRoot(engine: AgentEngine) {
    val cfg = engine.cfg
    val dark = when (cfg.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val pal = if (dark) DarkAgent else LightAgent
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snack = remember { SnackbarHostState() }
    var page by remember { mutableStateOf("chat") }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(engine.toast) {
        val t = engine.toast
        if (t != null) {
            engine.toast = null
            snack.showSnackbar(t)
        }
    }
    LaunchedEffect(engine.settingsRequest) {
        if (engine.settingsRequest > 0) showSettings = true
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        engine.addFiles(uris)
    }

    fun export() {
        val f = engine.exportFile() ?: return
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/markdown")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Export chat"))
    }

    fun go(p: String) {
        page = p
        scope.launch { drawer.close() }
    }

    CompositionLocalProvider(LocalAgent provides pal) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(290.dp),
                    drawerContainerColor = pal.sf,
                    drawerContentColor = pal.tx,
                ) {
                    Sidebar(
                        engine = engine,
                        dark = dark,
                        page = page,
                        onGo = { go(it) },
                        onSettings = { scope.launch { drawer.close() }; showSettings = true },
                        onExport = { export() },
                        onTheme = { engine.setTheme(if (dark) "light" else "dark") },
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxSize().background(pal.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
                Header(
                    title = when (page) {
                        "terminal" -> "Terminal"
                        "skills" -> "Skills"
                        "models" -> "Models"
                        else -> engine.cur?.title?.takeIf { it != "New chat" } ?: "CollabAgent"
                    },
                    model = if (cfg.mode == MODE_DEVICE) {
                        "On-device" + (if (cfg.localModel.isNotBlank()) "" else " (no model)")
                    } else if (cfg.key.isNotBlank()) cfg.model else "No API key",
                    onMenu = { scope.launch { drawer.open() } },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (page) {
                        "terminal" -> TerminalPage()
                        "skills" -> SkillsPage(engine)
                        "models" -> ModelsPage(engine)
                        else -> ChatBody(engine = engine, onAttach = { pickFiles.launch("*/*") })
                    }
                    SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp))
                }
            }
        }

        if (showSettings) {
            SettingsDialog(engine = engine, onClose = { showSettings = false })
        }
    }
}

@Composable
private fun Header(title: String, model: String, onMenu: () -> Unit) {
    val pal = LocalAgent.current
    Column {
        Row(
            Modifier.fillMaxWidth().background(pal.sf).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AgentButton("≡", onClick = onMenu)
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AgentPill(model, "ok")
        }
        HorizontalDivider(color = pal.bd)
    }
}

@Composable
private fun Sidebar(
    engine: AgentEngine,
    dark: Boolean,
    page: String,
    onGo: (String) -> Unit,
    onSettings: () -> Unit,
    onExport: () -> Unit,
    onTheme: () -> Unit,
) {
    val pal = LocalAgent.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CaLogo(34)
            Text("CollabAgent", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        AgentButton("+ New chat", variant = "pr", modifier = Modifier.fillMaxWidth(), enabled = !engine.busy) {
            engine.newChat()
            onGo("chat")
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(engine.chats) { chat ->
                val selected = chat === engine.cur && page == "chat"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chat.title,
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) pal.s2 else Color.Transparent)
                            .clickable(enabled = !engine.busy) {
                                engine.select(chat)
                                onGo("chat")
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        fontSize = 14.sp,
                        color = if (selected) pal.ac else pal.tx,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "×",
                        Modifier.clickable(enabled = !engine.busy) { engine.deleteChat(chat) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        color = pal.mu,
                        fontSize = 18.sp,
                    )
                }
            }
        }
        // Terminal status, like the "Backend Active" light in the design.
        val (dot, label) = when {
            TerminalEngine.shellAlive -> pal.ok to "Terminal active"
            TerminalEngine.setup is TerminalSetup.Running -> pal.wn to "Terminal setting up..."
            TerminalEngine.isInstalled(LocalContext.current) -> pal.wn to "Terminal ready (tap to start)"
            else -> pal.mu to "Terminal not set up yet"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(label, fontSize = 12.sp, color = pal.mu)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentButton("Skills", variant = if (page == "skills") "pr" else "", modifier = Modifier.weight(1f)) { onGo("skills") }
            AgentButton("Models", variant = if (page == "models") "pr" else "", modifier = Modifier.weight(1f)) { onGo("models") }
        }
        AgentButton("Terminal", variant = if (page == "terminal") "pr" else "", modifier = Modifier.fillMaxWidth()) { onGo("terminal") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentButton("⚙  Settings", modifier = Modifier.weight(1f), onClick = onSettings)
            AgentButton("⤓", onClick = onExport)
            AgentButton(if (dark) "☀" else "◐", onClick = onTheme)
        }
    }
}

@Composable
private fun ChatBody(engine: AgentEngine, onAttach: () -> Unit) {
    val pal = LocalAgent.current
    val cfg = engine.cfg
    val chat = engine.cur
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val count = chat?.ui?.size ?: 0
    val lastLen = (chat?.ui?.lastOrNull() as? com.collabcode.app.agent.Ai)?.text?.length ?: 0

    LaunchedEffect(count, engine.busy, lastLen / 200) {
        if (count > 0) listState.scrollToItem(count - 1)
    }

    fun submit() {
        if (engine.send(input)) input = ""
    }

    Column(Modifier.fillMaxSize()) {
        if (chat == null || chat.ui.isEmpty()) {
            Welcome(modifier = Modifier.weight(1f), onPick = { engine.send(it) })
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(chat.ui) { item -> ChatItemView(item, engine, cfg.fs, cfg.showThink) }
            }
        }

        Column(Modifier.fillMaxWidth().background(pal.bg).padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 10.dp)) {
            if (engine.files.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    itemsIndexed(engine.files) { i, f ->
                        Row(
                            Modifier.clip(RoundedCornerShape(99.dp)).border(1.dp, pal.bd, RoundedCornerShape(99.dp))
                                .background(pal.s2).padding(start = 10.dp, top = 3.dp, end = 4.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(f.name, fontSize = 12.sp)
                            Text("×", Modifier.clickable { engine.removeFile(i) }.padding(horizontal = 6.dp), color = pal.mu, fontSize = 14.sp)
                        }
                    }
                }
            }
            val box = RoundedCornerShape(24.dp)
            Row(
                Modifier.widthIn(max = 780.dp).fillMaxWidth()
                    .clip(box).border(1.dp, pal.bd, box).background(pal.sf).padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AgentButton("📎", modifier = Modifier.size(44.dp), onClick = onAttach)
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 220.dp).padding(horizontal = 6.dp, vertical = 10.dp),
                    textStyle = TextStyle(color = pal.tx, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(pal.ac),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) Text("Tell the agent what to do...", color = pal.mu, fontSize = 16.sp)
                            inner()
                        }
                    },
                )
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(pal.ac)
                        .clickable { if (engine.busy) engine.stop() else submit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (engine.busy) "■" else "↑", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Welcome(modifier: Modifier, onPick: (String) -> Unit) {
    val pal = LocalAgent.current
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        CaLogo(64)
        Text("CollabAgent", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Text(
            "Chat, plan, search the web, use your skills and run real commands in a real terminal. Pick a start or just type.",
            Modifier.padding(top = 6.dp, bottom = 18.dp).widthIn(max = 540.dp),
            color = pal.mu, fontSize = 14.sp, textAlign = TextAlign.Center,
        )
        Column(Modifier.widthIn(max = 540.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WELCOME_CHIPS.forEach { (label, prompt) ->
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, pal.bd, RoundedCornerShape(14.dp))
                        .background(pal.sf).clickable { onPick(prompt) }.padding(14.dp),
                ) {
                    Text(label, fontSize = 14.sp)
                }
            }
        }
    }
}
