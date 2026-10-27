package app.irremote.web

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SearchHit(val title: String, val url: String, val text: String)

/** A web search backend. Blocking; call off the main thread. Implementations throw [IOException] on failure. */
interface SearchProvider {
    val id: String
    fun search(query: String, objective: String, limit: Int): List<SearchHit>
}

internal object Http {
    fun request(url: String, method: String, headers: Map<String, String>, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 12_000; c.readTimeout = 25_000
            c.setRequestProperty("User-Agent", "IR-Remote/0.1 (+https://github.com/undertaker-afk/ir-remote)")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().take(2_000_000).toByteArray().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(120)}")
            return text
        } finally { c.disconnect() }
    }
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/**
 * Exa's hosted MCP server (https://mcp.exa.ai/mcp). It works without an API key on a rate-limited free plan; an optional
 * key lifts the limits. Speaks MCP "streamable HTTP": one JSON-RPC POST, answered as a server-sent event.
 */
class ExaMcpProvider(private val apiKey: String? = null, private val endpoint: String = "https://mcp.exa.ai/mcp") : SearchProvider {
    override val id = "exa"

    override fun search(query: String, objective: String, limit: Int): List<SearchHit> {
        val args = JSONObject().put("query", query).put("objective", objective).put("numResults", limit)
        val req = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
            .put("params", JSONObject().put("name", "web_search_exa").put("arguments", args))
        val headers = mutableMapOf("Content-Type" to "application/json", "Accept" to "application/json, text/event-stream")
        apiKey?.takeIf { it.isNotBlank() }?.let { headers["x-api-key"] = it }
        val body = Http.request(endpoint, "POST", headers, req.toString())
        return parseResponse(body)
    }

    companion object {
        /** SSE ("event: message / data: {...}") or plain JSON-RPC body -> hits. */
        fun parseResponse(body: String): List<SearchHit> {
            val data = body.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.toList()
            val json = JSONObject(if (data.isNotEmpty()) data.last() else body.trim())
            json.optJSONObject("error")?.let { throw IOException("Exa: ${it.optString("message")}") }
            val result = json.optJSONObject("result") ?: return emptyList()
            if (result.optBoolean("isError")) throw IOException("Exa: " + result.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty().take(120))
            val content = result.optJSONArray("content") ?: return emptyList()
            val text = (0 until content.length()).joinToString("\n") { content.getJSONObject(it).optString("text") }
            return parseText(text)
        }

        /** Exa returns blocks "Title: ..\nURL: ..\nPublished: ..\nAuthor: ..\nHighlights:\n.." separated by "---". */
        fun parseText(text: String): List<SearchHit> = text.split(Regex("\\n---\\n")).mapNotNull { blk ->
            val url = Regex("(?m)^URL: (.*)$").find(blk)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val title = Regex("(?m)^Title: (.*)$").find(blk)?.groupValues?.get(1)?.trim().orEmpty().let { if (it == "N/A") "" else it }
            SearchHit(title, url, blk.substringAfter("Highlights:", blk))
        }
    }
}

/** Tavily (api key, ~1,000 free credits per month): https://docs.tavily.com */
class TavilyProvider(private val key: String) : SearchProvider {
    override val id = "tavily"
    override fun search(query: String, objective: String, limit: Int): List<SearchHit> {
        val req = JSONObject().put("query", query).put("max_results", limit).put("search_depth", "basic")
        return parse(Http.request("https://api.tavily.com/search", "POST", mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $key"), req.toString()))
    }
    companion object {
        fun parse(body: String): List<SearchHit> {
            val a = JSONObject(body).optJSONArray("results") ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it).let { o -> SearchHit(o.optString("title"), o.optString("url"), o.optString("content")) } }
        }
    }
}

/** Brave Search API (api key, metered with monthly free credit): https://api-dashboard.search.brave.com */
class BraveProvider(private val key: String) : SearchProvider {
    override val id = "brave"
    override fun search(query: String, objective: String, limit: Int): List<SearchHit> =
        parse(Http.request("https://api.search.brave.com/res/v1/web/search?q=${Http.enc(query)}&count=$limit", "GET", mapOf("Accept" to "application/json", "X-Subscription-Token" to key)))
    companion object {
        fun parse(body: String): List<SearchHit> {
            val a = JSONObject(body).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
            return (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val extra = o.optJSONArray("extra_snippets")?.let { x -> (0 until x.length()).joinToString(" ") { i -> x.getString(i) } }.orEmpty()
                SearchHit(o.optString("title"), o.optString("url"), (o.optString("description") + " " + extra).trim())
            }
        }
    }
}

/** A SearXNG instance with the JSON format enabled (self-hosted; most public ones block bots or disable JSON). */
class SearxProvider(private val baseUrl: String) : SearchProvider {
    override val id = "searx"
    override fun search(query: String, objective: String, limit: Int): List<SearchHit> =
        parse(Http.request("${baseUrl.trimEnd('/')}/search?q=${Http.enc(query)}&format=json", "GET", mapOf("Accept" to "application/json")), limit)
    companion object {
        fun parse(body: String, limit: Int = 10): List<SearchHit> {
            val a = JSONObject(body).optJSONArray("results") ?: return emptyList()
            return (0 until minOf(a.length(), limit)).map { a.getJSONObject(it).let { o -> SearchHit(o.optString("title"), o.optString("url"), o.optString("content")) } }
        }
    }
}
