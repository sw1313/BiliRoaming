package me.custom.biliextras.sponsorblock

import me.custom.biliextras.BuildConfig
import me.custom.biliextras.utils.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

object SponsorBlockApi {
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 15000
    private const val REQUEST_ATTEMPTS = 2

    fun getSkipSegments(
        bvid: String,
        cid: Long,
        categories: Set<String>,
    ): Result<List<SponsorSegment>> = runCatching {
        val query = buildString {
            append("videoID=").append(bvid.urlEncode())
            append("&cid=").append(cid)
        }
        val response = getWithRetry("${SponsorBlockPrefs.server}/api/skipSegments?$query")
        if (response.code == 404) {
            SponsorBlockPrefs.setStatus("正常，无片段")
            return@runCatching emptyList()
        }
        check(response.code == 200) { "HTTP ${response.code}: ${response.body.take(120)}" }
        SponsorBlockPrefs.setStatus("正常")
        val array = JSONArray(response.body)
        buildList {
            for (i in 0 until array.length()) {
                val segment = SponsorSegment.fromJson(array.getJSONObject(i)) ?: continue
                if (segment.actionType == "skip") add(segment)
            }
        }
    }.onFailure {
        SponsorBlockPrefs.setStatus("异常：${it.message}")
        Log.w("SponsorBlock API failed: ${it.message}")
    }

    fun checkStatus(): Result<Boolean> = runCatching {
        val response = getWithRetry("${SponsorBlockPrefs.server}/api/status/uptime")
        val ok = response.code == 200
        SponsorBlockPrefs.setStatus(if (ok) "正常" else "异常：HTTP ${response.code}")
        ok
    }.onFailure {
        SponsorBlockPrefs.setStatus("异常：${it.message}")
        Log.w("SponsorBlock status failed: ${it.message}")
    }

    fun userInfo(): Result<String> = runCatching {
        val values = JSONArray(listOf("viewCount", "minutesSaved", "segmentCount")).toString()
        val query = "userID=${SponsorBlockPrefs.userId.urlEncode()}&values=${values.urlEncode()}"
        val response = getWithRetry("${SponsorBlockPrefs.server}/api/userInfo?$query")
        check(response.code == 200) { "HTTP ${response.code}: ${response.body.take(120)}" }
        val obj = JSONObject(response.body)
        val text = buildString {
            append("查看次数：").append(obj.optLong("viewCount", 0L)).append(" 次")
            append("；节省时间：").append(obj.optLong("minutesSaved", 0L)).append(" 分钟")
            append("；提交片段：").append(obj.optLong("segmentCount", 0L)).append(" 个")
        }
        SponsorBlockPrefs.setUserInfo(text)
        text
    }.onFailure {
        val msg = "异常：${it.message}"
        SponsorBlockPrefs.setUserInfo(msg)
        Log.w("SponsorBlock user info failed: ${it.message}")
    }

    fun viewedVideoSponsorTime(uuid: String): Result<Boolean> = runCatching {
        val response = post(
            "${SponsorBlockPrefs.server}/api/viewedVideoSponsorTime",
            """{"UUID":"${uuid.jsonEscape()}"}""",
        )
        response.code == 200
    }.onFailure {
        Log.w("SponsorBlock track failed: ${it.message}")
    }

    data class SubmitSegment(
        val start: Double,
        val end: Double,
        val category: String,
        val actionType: String = "skip",
    )

    fun submitSegments(
        bvid: String,
        segments: List<SubmitSegment>,
    ): Result<String> = runCatching {
        val segmentArray = JSONArray()
        segments.forEach { seg ->
            segmentArray.put(
                JSONObject().apply {
                    put("segment", JSONArray().put(seg.start).put(seg.end))
                    put("category", seg.category)
                    put("actionType", seg.actionType)
                },
            )
        }
        val body = JSONObject().apply {
            put("userID", SponsorBlockPrefs.userId)
            put("videoID", bvid)
            put("segments", segmentArray)
        }.toString()
        val response = postWithCacheBypass("${SponsorBlockPrefs.server}/api/skipSegments", body)
        check(response.code in 200..299) { "HTTP ${response.code}: ${response.body.take(120)}" }
        response.body
    }.onFailure {
        Log.w("SponsorBlock submit failed: ${it.message}")
    }

    fun voteOnSponsorTime(uuid: String, type: Int, category: String? = null): Result<Boolean> = runCatching {
        val body = JSONObject().apply {
            put("UUID", uuid)
            put("userID", SponsorBlockPrefs.userId)
            put("type", type)
            if (category != null) put("category", category)
        }.toString()
        val response = postWithCacheBypass("${SponsorBlockPrefs.server}/api/voteOnSponsorTime", body)
        response.code in 200..299
    }.onFailure {
        Log.w("SponsorBlock vote failed: ${it.message}")
    }

    private fun postWithCacheBypass(url: String, body: String): HttpResponse {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("content-type", "application/json; charset=utf-8")
            setRequestProperty("origin", "BiliExtras")
            setRequestProperty("x-ext-version", BuildConfig.VERSION_NAME)
            setRequestProperty("user-agent", "BiliExtras/${BuildConfig.VERSION_NAME}")
            setRequestProperty("x-skip-cache", "1")
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            return HttpResponse(connection.responseCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun getWithRetry(url: String): HttpResponse {
        var lastError: Throwable? = null
        repeat(REQUEST_ATTEMPTS) { index ->
            val startedAt = System.currentTimeMillis()
            try {
                val response = get(url)
                Log.x(
                    "SponsorBlock API GET ok code=${response.code}, " +
                        "attempt=${index + 1}/$REQUEST_ATTEMPTS, elapsed=${System.currentTimeMillis() - startedAt}ms",
                )
                return response
            } catch (error: Throwable) {
                lastError = error
                Log.x(
                    "SponsorBlock API GET failed attempt=${index + 1}/$REQUEST_ATTEMPTS, " +
                        "elapsed=${System.currentTimeMillis() - startedAt}ms, " +
                        "error=${error.javaClass.simpleName}: ${error.message}, url=${url.take(200)}",
                )
                if (index + 1 < REQUEST_ATTEMPTS) {
                    Thread.sleep(500L)
                }
            }
        }
        throw lastError ?: IllegalStateException("request failed")
    }

    private fun get(url: String): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("origin", "BiliExtras")
            setRequestProperty("x-ext-version", BuildConfig.VERSION_NAME)
            setRequestProperty("user-agent", "BiliExtras/${BuildConfig.VERSION_NAME}")
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            return HttpResponse(connection.responseCode, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun post(url: String, body: String): HttpResponse {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("content-type", "application/json; charset=utf-8")
            setRequestProperty("origin", "BiliExtras")
            setRequestProperty("x-ext-version", BuildConfig.VERSION_NAME)
            setRequestProperty("user-agent", "BiliExtras/${BuildConfig.VERSION_NAME}")
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            return HttpResponse(connection.responseCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
}
