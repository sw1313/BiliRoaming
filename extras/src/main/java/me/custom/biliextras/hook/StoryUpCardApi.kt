package me.custom.biliextras.hook

import me.custom.biliextras.utils.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Query UP display name by mid; used for settings summary and confirm dialogs (mid is authoritative). */
object StoryUpCardApi {
    private const val CARD_URL = "https://api.bilibili.com/x/web-interface/card"
    private const val SEARCH_URL = "https://api.bilibili.com/x/web-interface/search/type"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 12000
    private const val USER_AGENT = "Mozilla/5.0 BiliExtras"
    private const val REFERER = "https://www.bilibili.com"

    private val nameCache = ConcurrentHashMap<Long, String>()
    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun cachedName(mid: Long): String? = nameCache[mid]

    /** Main-thread safe: uses cache only; callers should prefetch on a worker when they need names. */
    fun formatDisplay(mid: Long): String {
        val name = cachedName(mid)
        return if (name.isNullOrBlank()) mid.toString() else "$name ($mid)"
    }

    /** Worker-thread helper for places that intentionally need an immediate network-backed label. */
    fun formatDisplayBlocking(mid: Long): String {
        val name = cachedName(mid) ?: fetchNameByMid(mid)
        return if (name.isNullOrBlank()) mid.toString() else "$name ($mid)"
    }

    fun fetchNameByMid(mid: Long): String? = runCatching {
        nameCache[mid]?.let { return@runCatching it }
        val body = httpGet("$CARD_URL?mid=$mid&photo=false") ?: run {
            Log.trace { "StoryUpCardApi: card request failed mid=$mid" }
            return@runCatching null
        }
        val json = JSONObject(body)
        if (json.optInt("code", -1) != 0) {
            Log.trace { "StoryUpCardApi: card code=${json.optInt("code")} mid=$mid" }
            return@runCatching null
        }
        json.optJSONObject("data")
            ?.optJSONObject("card")
            ?.optString("name")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.also { nameCache[mid] = it }
    }.getOrNull()

    /** Resolve nickname to mid via public search; first exact name match preferred. */
    fun resolveMidByNickname(nickname: String): Long? = runCatching {
        val keyword = nickname.trim()
        if (keyword.isEmpty()) return@runCatching null
        val query = "search_type=bili_user&keyword=${keyword.urlEncode()}&page=1"
        val body = httpGet("$SEARCH_URL?$query") ?: return@runCatching null
        val json = JSONObject(body)
        if (json.optInt("code", -1) != 0) return@runCatching null
        val results = json.optJSONObject("data")?.optJSONArray("result") ?: return@runCatching null
        var fallbackMid: Long? = null
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val mid = item.optLong("mid", 0L).takeIf { it > 0L } ?: continue
            val uname = item.optString("uname", "").trim()
            if (fallbackMid == null) fallbackMid = mid
            if (uname.equals(keyword, ignoreCase = true)) {
                if (uname.isNotEmpty()) nameCache[mid] = uname
                return@runCatching mid
            }
        }
        fallbackMid?.also { mid ->
            fetchNameByMid(mid)
        }
    }.getOrNull()

    fun prefetchNames(mids: Collection<Long>) {
        mids.forEach { mid ->
            if (nameCache[mid] == null) fetchNameByMid(mid)
        }
    }

    fun prefetchNamesAsync(mids: Collection<Long>) {
        val pending = mids.filter { nameCache[it] == null }
        if (pending.isEmpty()) return
        ioExecutor.execute { prefetchNames(pending) }
    }

    private fun httpGet(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", REFERER)
            setRequestProperty("Connection", "keep-alive")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                runCatching { conn.errorStream?.use { it.readBytes() } }
                return@runCatching null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())
}
