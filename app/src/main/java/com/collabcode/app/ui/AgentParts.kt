package com.collabcode.app.ui

import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.collabcode.app.agent.AgentEngine
import com.collabcode.app.agent.Ai
import com.collabcode.app.agent.Me
import com.collabcode.app.agent.Note
import com.collabcode.app.agent.ToolCard
import com.collabcode.app.agent.ToolItem
import com.collabcode.app.agent.Ui
import com.collabcode.app.data.ApkInstaller
import kotlinx.coroutines.launch
import java.io.File

// ---------- small controls (the design: emerald, rounded, soft) ----------

@Composable
internal fun CaLogo(size: Int = 30) {
    val pal = LocalAgent.current
    Box(
        Modifier.size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(Brush.linearGradient(listOf(pal.ac, pal.ac2))),
        contentAlignment = Alignment.Center,
    ) {
        Text("CA", color = Color.White, fontSize = (size * 0.38f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun AgentButton(
    label: String,
    variant: String = "",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val pal = LocalAgent.current
    val shape = RoundedCornerShape(12.dp)
    val bg = when (variant) {
        "pr" -> pal.ac
        "ok" -> pal.ok
        else -> pal.s2
    }
    val border = when (variant) {
        "pr" -> pal.ac
        "ok" -> pal.ok
        else -> pal.bd
    }
    val tx = when (variant) {
        "pr", "ok" -> Color.White
        "dn" -> pal.er
        else -> pal.tx
    }
    Box(
        modifier.clip(shape)
            .border(1.dp, border, shape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) tx else tx.copy(alpha = 0.45f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun AgentPill(text: String, kind: String) {
    val pal = LocalAgent.current
    val c = when (kind) {
        "ok" -> pal.ok
        "warn" -> pal.wn
        "err" -> pal.er
        else -> pal.ac
    }
    val shape = RoundedCornerShape(99.dp)
    Text(
        text,
        Modifier.clip(shape)
            .border(1.dp, c.copy(alpha = 0.35f), shape)
            .background(c.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = c,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

@Composable
internal fun TextAction(label: String, onClick: () -> Unit) {
    val pal = LocalAgent.current
    Text(
        label,
        Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        color = pal.ac,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

// ---------- chat items ----------

@Composable
internal fun ChatItemView(item: Ui, engine: AgentEngine, fs: Int, showThink: Boolean) {
    val size = fs.sp
    when (item) {
        is Me -> UserBubble(item.text, size)
        is Ai -> AiBubble(item, engine, size, showThink)
        is Note -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(item.text, fontStyle = FontStyle.Italic, color = LocalAgent.current.mu, fontSize = size)
        }
        is ToolItem -> ToolView(item.card, engine)
    }
}

@Composable
private fun UserBubble(text: String, fs: TextUnit) {
    val pal = LocalAgent.current
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 560.dp)) {
            SelectionContainer {
                Text(
                    text,
                    Modifier.clip(RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                        .background(pal.ac)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    color = Color.White,
                    fontSize = fs,
                    lineHeight = fs * 1.45f,
                )
            }
            TextAction("Copy") { clipboard.setText(AnnotatedString(text)) }
        }
    }
}

@Composable
private fun AiBubble(ai: Ai, engine: AgentEngine, fs: TextUnit, showThink: Boolean) {
    val pal = LocalAgent.current
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CaLogo(30)
        Column(Modifier.weight(1f)) {
            if (showThink && ai.think.isNotBlank()) {
                ThinkBlock(ai.think, streaming = ai.text.isEmpty() && engine.busy, fs = fs)
            }
            if (ai.text.isEmpty() && ai.think.isEmpty()) {
                Text("Thinking...", fontStyle = FontStyle.Italic, color = pal.mu, fontSize = fs)
            } else if (ai.text.isNotEmpty()) {
                SelectionContainer {
                    AgentMarkdown(ai.text, fs, pal, engine) { clipboard.setText(AnnotatedString(it)) }
                }
                TextAction("Copy reply") { clipboard.setText(AnnotatedString(ai.text)) }
            }
        }
    }
}

/** The model's reasoning (chain of thought). Open while it streams, tap to open or close. */
@Composable
private fun ThinkBlock(text: String, streaming: Boolean, fs: TextUnit) {
    val pal = LocalAgent.current
    var open by remember { mutableStateOf(streaming) }
    LaunchedEffect(streaming) { if (!streaming) open = false }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(shape)
            .border(1.dp, pal.bd, shape)
            .background(pal.s2),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (streaming) "Thinking..." else "Thought process",
                color = pal.ac, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(if (open) "Hide" else "Show", color = pal.mu, fontSize = 12.sp)
        }
        if (open) {
            SelectionContainer {
                Text(
                    text,
                    Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    color = pal.mu,
                    fontSize = (fs.value - 1f).sp,
                    lineHeight = (fs.value * 1.4f).sp,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}

@Composable
private fun ToolView(card: ToolCard, engine: AgentEngine) {
    val pal = LocalAgent.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(16.dp)
    val terminalLike = card.title == "run_command"
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 780.dp).fillMaxWidth()
                .clip(shape)
                .border(1.dp, pal.bd, shape)
                .background(pal.sf)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(card.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                AgentPill(card.status, card.kind)
            }
            Text(
                card.sub,
                Modifier.padding(top = 2.dp, bottom = 6.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = pal.mu,
                maxLines = 3,
            )
            if (card.out.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        card.out,
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (terminalLike) Color(0xFF020617) else pal.bg)
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = if (terminalLike) Color(0xFF34D399) else pal.tx,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextAction("Copy output") { clipboard.setText(AnnotatedString(card.out)) }
                }
            }
            if (card.file.isNotEmpty()) {
                val f = File(card.file)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (f.name.endsWith(".apk", ignoreCase = true)) {
                        AgentButton("Install " + f.name, variant = "pr", modifier = Modifier.weight(1f), enabled = f.exists()) {
                            ApkInstaller.install(ctx, f)
                        }
                    }
                    AgentButton("Share", modifier = Modifier.weight(if (f.name.endsWith(".apk", true)) 0.5f else 1f), enabled = f.exists()) {
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("application/octet-stream")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(Intent.createChooser(send, "Share file"))
                    }
                }
            }
            if (card.pending) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    AgentButton("Deny", variant = "dn") { engine.decide(card, false) }
                    Spacer(Modifier.width(8.dp))
                    AgentButton("Allow", variant = "ok") { engine.decide(card, true) }
                }
            }
        }
    }
}

// ---------- markdown with real Run buttons ----------

private class Block(val code: Boolean, val lang: String, val body: String)

private val FENCE = Regex("```([\\w+-]*)[ \\t]*\\n?([\\s\\S]*?)(```|$)")
private val INLINE = Regex("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*|\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")

private fun splitBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    var last = 0
    for (m in FENCE.findAll(text)) {
        if (m.range.first > last) out.add(Block(false, "", text.substring(last, m.range.first)))
        out.add(Block(true, m.groupValues[1].ifEmpty { "code" }, m.groupValues[2].removeSuffix("\n")))
        last = m.range.last + 1
    }
    if (last < text.length) out.add(Block(false, "", text.substring(last)))
    return out
}

private fun inline(s: String, pal: AgentPalette): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(last, m.range.first))
        val code = m.groups[1]
        val bold = m.groups[2]
        val link = m.groups[3]
        val url = m.groups[4]
        when {
            code != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = pal.s2)) { append(code.value) }
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.value) }
            link != null && url != null -> withLink(
                LinkAnnotation.Url(url.value, TextLinkStyles(SpanStyle(color = pal.ac, textDecoration = TextDecoration.Underline)))
            ) { append(link.value) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

private val RUN_LANGS = setOf("bash", "sh", "shell", "zsh", "console", "python", "py", "python3", "javascript", "js", "node")
private val HTML_LANGS = setOf("html", "htm")

@Composable
internal fun AgentMarkdown(text: String, fs: TextUnit, pal: AgentPalette, engine: AgentEngine, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in splitBlocks(text)) {
            if (b.code) {
                CodeBlock(b.lang, b.body, engine, pal, onCopy)
            } else {
                for (line in b.body.split("\n")) {
                    when {
                        line.isBlank() -> Spacer(Modifier.height(4.dp))
                        line.startsWith("#") -> Text(
                            inline(line.trimStart('#', ' '), pal),
                            fontWeight = FontWeight.Bold,
                            fontSize = fs * 1.05f,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        line.startsWith("- ") || line.startsWith("* ") -> Row {
                            Text("•  ", fontSize = fs)
                            Text(inline(line.substring(2), pal), fontSize = fs, lineHeight = fs * 1.45f)
                        }
                        else -> Text(inline(line, pal), fontSize = fs, lineHeight = fs * 1.5f)
                    }
                }
            }
        }
    }
}

private class RunEntry(val code: String) {
    var output by mutableStateOf("Running...")
    var ok by mutableStateOf(true)
    var done by mutableStateOf(false)
    var showCode by mutableStateOf(false)
}

@Composable
private fun CodeBlock(lang: String, body: String, engine: AgentEngine, pal: AgentPalette, onCopy: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val runs = remember { mutableStateListOf<RunEntry>() }
    var preview by remember { mutableStateOf(false) }
    val l = lang.lowercase()
    val runnable = l in RUN_LANGS
    val isHtml = l in HTML_LANGS
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, pal.bd, shape)) {
            Row(
                Modifier.fillMaxWidth().background(pal.s2).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(lang, fontSize = 12.sp, color = pal.mu, fontFamily = FontFamily.Monospace)
                Row {
                    TextAction("Copy") { onCopy(body) }
                    if (runnable) {
                        TextAction("▶ Run") {
                            val e = RunEntry(body)
                            runs.add(0, e)
                            scope.launch {
                                val r = engine.runSnippet(l, body)
                                e.output = r.output
                                e.ok = r.ok
                                e.done = true
                            }
                        }
                    }
                    if (isHtml) TextAction("▶ Run") { preview = true }
                }
            }
            Text(
                body,
                Modifier.fillMaxWidth()
                    .background(pal.sf)
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = pal.tx,
            )
        }
        for (r in runs) {
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF020617))
                    .padding(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (!r.done) "Running..." else if (r.ok) "Done" else "Failed",
                        color = if (!r.done) pal.wn else if (r.ok) Color(0xFF34D399) else pal.er,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Row {
                        TextAction(if (r.showCode) "Hide code" else "View code") { r.showCode = !r.showCode }
                        TextAction("Copy output") { onCopy(r.output) }
                    }
                }
                if (r.showCode) {
                    Text(
                        r.code,
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp,
                        color = Color(0xFF94A3B8),
                    )
                }
                Text(
                    r.output,
                    Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                    color = Color(0xFF34D399),
                )
            }
        }
    }
    if (preview) HtmlPreview(body) { preview = false }
}

/** Runs an HTML page from the chat in a real WebView. The Code button switches to the source. */
@Composable
private fun HtmlPreview(html: String, onClose: () -> Unit) {
    val pal = LocalAgent.current
    val clipboard = LocalClipboardManager.current
    var showCode by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(pal.sf).statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("HTML run", fontWeight = FontWeight.SemiBold, color = pal.tx)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentButton(if (showCode) "Run" else "Code") { showCode = !showCode }
                    AgentButton("Copy") { clipboard.setText(AnnotatedString(html)) }
                    AgentButton("Close", variant = "pr", onClick = onClose)
                }
            }
            if (showCode) {
                SelectionContainer {
                    Text(
                        html,
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()).padding(12.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = pal.tx,
                    )
                }
            } else {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = WebViewClient()
                            loadDataWithBaseURL("https://preview.local/", html, "text/html", "utf-8", null)
                        }
                    },
                )
            }
        }
    }
}
