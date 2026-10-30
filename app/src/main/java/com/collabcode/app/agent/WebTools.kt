package com.collabcode.app.agent

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Free web search (no API key) and page reading. Search pages are read like a browser would. */
object WebTools {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private class Hit(val title: String, val url: String, val snippet: String)

    fun search(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) throw IOException("query is required")
        val errors = mutableListOf<String>()
        for (engine in listOf(::duckDuckGo, ::bing)) {
            try {
                val hits = engine(q)
                if (hits.isNotEmpty()) {
                    return hits.take(8).mapIndexed { i, h ->
                        "${i + 1}. ${h.title}\n   ${h.url}\n   ${h.snippet}"
                    }.joinToString("\n")
                }
            } catch (e: Exception) {
                errors.add(e.message ?: e.toString())
            }
        }
        return "No results." + (if (errors.isNotEmpty()) " (" + errors.joinToString("; ").take(200) + ")" else "")
    }

    private fun duckDuckGo(q: String): List<Hit> {
        val html = request("https://html.duckduckgo.com/html/", "q=" + URLEncoder.encode(q, "UTF-8"))
        val links = Regex("<a\\s[^>]*result__a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("<a\\s[^>]*result__snippet[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        return links.mapIndexedNotNull { i, m ->
            val href = Regex("href=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val url = cleanDdgUrl(decodeEntities(href))
            if (!url.startsWith("http")) return@mapIndexedNotNull null
            Hit(text(m.groupValues[1]), url, snippets.getOrNull(i)?.let { text(it.groupValues[1]) }.orEmpty())
        }
    }

    private fun cleanDdgUrl(u: String): String {
        val full = if (u.startsWith("//")) "https:$u" else u
        val m = Regex("[?&]uddg=([^&]+)").find(full)
        return if (m != null) URLDecoder.decode(m.groupValues[1], "UTF-8") else full
    }

    private fun bing(q: String): List<Hit> {
        val html = request("https://www.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8"), null)
        val blocks = Regex("<li class=\"b_algo\"[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(html)
        return blocks.mapNotNull { b ->
            val a = Regex("<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b.value)
                ?: return@mapNotNull null
            val snip = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(b.value)?.groupValues?.get(1).orEmpty()
            val url = decodeEntities(a.groupValues[1])
            if (!url.startsWith("http")) null else Hit(text(a.groupValues[2]), url, text(snip))
        }.toList()
    }

    /** Reads a web page and returns its plain text. */
    fun fetch(url: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw IOException("Give a full http or https address.")
        val html = request(url, null)
        val body = html
            .replace(Regex("<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        val plain = text(body).replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()
        return if (plain.isEmpty()) "(the page has no readable text)" else plain.take(9000)
    }

    private fun request(url: String, postBody: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            if (postBody != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < 1_500_000) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
        } finally {
            c.disconnect()
        }
    }

    private fun text(html: String): String = decodeEntities(html.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()

    private fun decodeEntities(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ")
}
