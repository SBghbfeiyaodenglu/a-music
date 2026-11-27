package com.amusic.player.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 一条候选歌词（来自在线歌词接口的公开 API）。
 *
 * @param hasTimeline true 表示这条是带时间戳的 LRC（能滚动高亮），false 是纯文本歌词
 * @param lyrics 要保存的正文：**优先用带时间轴的**，没有才用纯文本
 * @param instrumental 接口标记的"纯音乐"，这种通常没有歌词
 */
data class LyricsCandidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Int,
    val instrumental: Boolean,
    val hasTimeline: Boolean,
    val lyrics: String,
)

sealed interface LyricsSearchResult {
    data class Ok(val candidates: List<LyricsCandidate>) : LyricsSearchResult
    data class Failed(val message: String) : LyricsSearchResult
}

/**
 * 在线歌词来源：只在用户主动用「在线搜词」时才联网的公开歌词接口。
 *
 * 为什么不做浏览器抓取、为什么用这个接口：
 *
 * ```
 * 1) 浏览器抓取（内嵌浏览器 + 启发式找歌词）不能做：站点什么结构就得猜什么结构，
 *    抓回来经常没有时间戳，也不存在一个歌词齐全的网站。
 * 2) 用一个公开的歌词接口就够了，它专门做 LRC 歌词，而且**有公开 API**：
 *    GET /api/search?q=关键词 → JSON，直接给出 syncedLyrics（带时间轴）和 plainLyrics，
 *    还带 duration（秒）。不用抓页面、不用读剪贴板、不会因为改版而失效。
 * 3) duration 特别有用：同一首歌经常有多个版本（录音室/现场/伴奏），
 *    拿本地文件的时长和候选的时长一比，就能挑到对的那一条（见 pickBest）。
 * ```
 *
 * 没引网络库（Retrofit / OkHttp）——只有这一个 GET 请求，`HttpURLConnection` 足够，
 * 少一个依赖少一份体积。这个类是全应用**唯一**联网的地方。
 */
class OnlineLyricsClient {

    suspend fun search(query: String): LyricsSearchResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext LyricsSearchResult.Ok(emptyList())

        val url = URL("$BASE_URL/api/search?q=" + URLEncoder.encode(trimmed, "UTF-8"))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // 接口方的礼貌要求：带上自己的项目名，别让请求看起来像匿名爬虫
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }

        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // 5xx 是服务端自己出问题了，跟"连不上"一样按挂了处理
                return@withContext if (code >= 500) {
                    LyricsSearchResult.Failed(CONNECT_FAILED)
                } else {
                    LyricsSearchResult.Failed("歌词服务返回 HTTP $code")
                }
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            LyricsSearchResult.Ok(parse(body))
        } catch (e: CancellationException) {
            throw e                    // 协程被取消要照实往上抛，别当成"连不上"
        } catch (e: Exception) {
            // 网络不通、超时、被墙……统一给用户看得懂的一句话
            // （文案固定为："歌词库无法连接，可能挂了~请稍后再试..."）
            LyricsSearchResult.Failed(CONNECT_FAILED)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * 取一个"可能是 null"的字符串字段。
     *
     * ⚠ 不能用 `optString(k).takeIf { it.isNotBlank() }`：字段值是 JSON null 时
     *   Android 的 optString 会返回字符串 "null"，非空判断挡不住，
     *   结果纯音乐候选的歌词正文就变成四个字母 "null" 被存下来。
     */
    private fun jsonText(item: JSONObject, key: String): String? =
        if (item.isNull(key)) null else item.optString(key).takeIf { it.isNotBlank() }

    private fun parse(body: String): List<LyricsCandidate> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<LyricsCandidate>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            // ⚠ 必须用 isNull 判一下：字段是 JSON null 时 optString 会返回**字符串 "null"**，
            //   而 "null".isNotBlank() 是 true —— 纯音乐/只有纯文本的候选会被存成正文 "null"。
            val synced = jsonText(item, "syncedLyrics")
            val plain = jsonText(item, "plainLyrics")
            val lyrics = synced ?: plain ?: continue
            result += LyricsCandidate(
                id = item.optLong("id"),
                trackName = item.optString("trackName").ifBlank { item.optString("name") },
                artistName = item.optString("artistName"),
                albumName = item.optString("albumName"),
                durationSec = item.optDouble("duration", 0.0).toInt(),
                instrumental = item.optBoolean("instrumental", false),
                hasTimeline = synced != null,
                lyrics = lyrics,
            )
        }
        return result
    }

    private companion object {
        const val BASE_URL = "https://lrclib.net"

        /** 连不上/服务端挂了时给用户看的话 */
        const val CONNECT_FAILED = "歌词库无法连接，可能挂了~请稍后再试..."
        const val TIMEOUT_MS = 12_000
        const val USER_AGENT = "A-Music-Android/1.0 (personal local music player)"
    }
}

