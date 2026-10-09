package sk.fleer.kopia

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stream extraction over plain HTTP + JSON.
 *
 * No JavaScript engine and no yt-dlp. YouTube's *web* player no longer puts
 * stream URLs in the page at all - it only exposes a serverAbrStreamingUrl and
 * talks SABR (protobuf request/response) - so reconstructing the web player is
 * both hard and pointless here. But a few of YouTube's app clients still answer
 * the plain InnerTube player endpoint with ordinary direct URLs, and need no JS
 * player to do it.
 *
 * Only VISIONOS is usable, measured 2026-10-09. ANDROID and IOS have gone
 * SABR-only (OK, but no format carries a url). ANDROID_VR looks like a fallback
 * and is a trap: its URLs serve the first megabyte or two and then answer
 * HTTP 403, because downloads through it need a GVS PO token (yt-dlp marks it
 * required). Falling back to it turned an honest "not available" into a
 * megabyte of wasted download and a confusing error. If VISIONOS refuses a
 * video, nothing here can fetch it - say so and stop.
 */
object Youtube {

    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
    private const val WATCH_URL = "https://www.youtube.com/watch?v="

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    private class Client(
        val name: String,
        val version: String,
        val contextName: Int,
        val userAgent: String,
        val extra: Map<String, Any>,
    )

    private val client = Client(
        name = "VISIONOS", version = "1.02", contextName = 101,
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        extra = mapOf(
            "deviceMake" to "Apple",
            "deviceModel" to "RealityDevice17,1",
            "osName" to "visionOS",
            "osVersion" to "26.5.23O471",
        ),
    )

    private const val ATTEMPTS = 3

    /**
     * The visitor identity, kept between videos. It is not tied to the video whose
     * page it came from (measured: one token served four other tracks fine), and
     * getting it costs a ~1.3 MB watch page - per track, that was ~14 MB of HTML
     * for an 11-song playlist. Refreshed only after a refusal.
     */
    @Volatile
    private var visitor: String? = null

    class NotAvailable(message: String) : IOException(message)

    fun videoId(input: String): String {
        val text = input.trim()
        Regex("""(?:v=|/shorts/|youtu\.be/|/embed/|/live/)([A-Za-z0-9_-]{11})""")
            .find(text)?.let { return it.groupValues[1] }
        if (text.matches(Regex("""[A-Za-z0-9_-]{11}"""))) return text
        throw NotAvailable("V odkaze som nenašiel ID videa.")
    }

    fun playlistId(input: String): String? =
        Regex("""[?&]list=([A-Za-z0-9_-]+)""").find(input.trim())?.groupValues?.get(1)
            // Auto-generated "mix" playlists are endless and personalised; there
            // is no fixed track list to download.
            ?.takeUnless { it.startsWith("RD") }

    /**
     * Reads a playlist's track list out of the playlist page.
     *
     * Walks the JSON looking for entries anywhere rather than following a fixed
     * path: YouTube moved this data from `playlistVideoRenderer` to
     * `lockupViewModel` at some point, and a hardcoded path would simply have
     * returned an empty list.
     */
    fun playlist(id: String): Playlist {
        val html = get("https://www.youtube.com/playlist?list=$id", DESKTOP_UA)
        val data = embeddedJson(html, "ytInitialData")
            ?: throw NotAvailable("Playlist sa nepodarilo načítať.")

        val entries = mutableListOf<JSONObject>()
        collect(data, "lockupViewModel", entries)

        val items = entries.mapNotNull { entry ->
            if (entry.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return@mapNotNull null
            val videoId = entry.optString("contentId").ifBlank { null } ?: return@mapNotNull null
            val titles = mutableListOf<String>()
            collectStrings(entry.optJSONObject("metadata"), "content", titles)
            PlaylistItem(videoId, titles.firstOrNull()?.ifBlank { null } ?: videoId)
        }.distinctBy { it.videoId }

        if (items.isEmpty()) throw NotAvailable("V playliste som nenašiel žiadne videá.")

        val titles = mutableListOf<String>()
        collectStrings(data.optJSONObject("microformat"), "title", titles)
        return Playlist(titles.firstOrNull()?.ifBlank { null } ?: "Playlist", items)
    }

    private fun embeddedJson(html: String, marker: String): JSONObject? {
        val at = html.indexOf(marker)
        if (at < 0) return null
        val start = html.indexOf('{', at)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return runCatching { JSONObject(html.substring(start, i + 1)) }.getOrNull()
                }
            }
        }
        return null
    }

    private fun collect(node: Any?, key: String, into: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> for (name in node.keys()) {
                val value = node.get(name)
                if (name == key && value is JSONObject) into += value else collect(value, key, into)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.get(i), key, into)
        }
    }

    private fun collectStrings(node: Any?, key: String, into: MutableList<String>) {
        when (node) {
            is JSONObject -> for (name in node.keys()) {
                val value = node.get(name)
                if (name == key && value is String) into += value else collectStrings(value, key, into)
            }
            is JSONArray -> for (i in 0 until node.length()) collectStrings(node.get(i), key, into)
        }
    }

    /**
     * A refusal is retried with a fresh visitor identity. It is not reliably
     * final: a playlist track that VISIONOS refused once downloaded fine on the
     * next run, while the same client answered 15 of 15 back-to-back requests
     * cleanly. Genuinely blocked videos just cost two extra small requests.
     */
    fun resolve(url: String): Media {
        val id = videoId(url)
        var problem = "Video sa nedá stiahnuť."

        for (attempt in 1..ATTEMPTS) {
            if (attempt > 1) {
                visitor = null
                Thread.sleep(2000L * (attempt - 1))
            }
            try {
                // A bare player request gets "Sign in to confirm you're not a
                // bot"; the visitorData from a watch page is what makes it pass.
                val identity = visitor ?: visitorData(id).also { visitor = it }
                val response = player(id, client, identity)
                val status = response.optJSONObject("playabilityStatus")
                val state = status?.optString("status").orEmpty()
                if (state == "OK") {
                    parse(response, client.userAgent)?.let { return it }
                    problem = "YouTube vrátil video bez priamych adries."
                } else {
                    problem = status?.optString("reason").orEmpty().ifBlank { state }
                }
            } catch (e: IOException) {
                problem = e.message ?: e.javaClass.simpleName
            } catch (e: org.json.JSONException) {
                problem = "Neúplná odpoveď od YouTube."
            }
        }
        throw NotAvailable(problem)
    }

    private fun parse(response: JSONObject, userAgent: String): Media? {
        val streaming = response.optJSONObject("streamingData") ?: return null
        val formats = streaming.optJSONArray("adaptiveFormats") ?: return null

        var audio: Stream? = null
        val videos = mutableListOf<Stream>()
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val url = f.optString("url").ifBlank { null } ?: continue
            val mime = f.optString("mimeType")
            val stream = Stream(
                itag = f.optInt("itag"),
                url = url,
                mimeType = mime,
                bytes = f.optString("contentLength").toLongOrNull() ?: continue,
                bitrate = f.optInt("bitrate"),
                height = f.optInt("height"),
                label = f.optString("qualityLabel").ifBlank { null },
                userAgent = userAgent,
            )
            // AAC in MP4 and H.264 specifically: those are the two things every
            // Android device, TV and player handles, and MediaMuxer can put them
            // in an MP4 container without re-encoding. Opus/VP9/AV1 would need a
            // WebM muxer or a transcode. This is also why 1080p is the ceiling -
            // above that YouTube only ships VP9/AV1.
            if (mime.startsWith("audio/mp4")) {
                if (audio == null || stream.bitrate > audio.bitrate) audio = stream
            } else if (mime.contains("avc1") && stream.height > 0) {
                videos += stream
            }
        }

        if (audio == null) return null

        val details = response.optJSONObject("videoDetails")
        return Media(
            title = details?.optString("title").orEmpty().ifBlank { "Video" },
            author = details?.optString("author").orEmpty().ifBlank { "YouTube" },
            seconds = details?.optString("lengthSeconds")?.toIntOrNull() ?: 0,
            audio = audio,
            // One entry per resolution (the fattest, i.e. best, of each), best
            // first - YouTube lists several bitrates per height.
            videos = videos
                .groupBy { it.height }
                .map { (_, group) -> group.maxBy { it.bitrate } }
                .sortedByDescending { it.height },
        )
    }

    private fun visitorData(id: String): String? = try {
        val html = get(WATCH_URL + id, DESKTOP_UA)
        Regex(""""VISITOR_DATA":"([^"]+)"""").find(html)?.groupValues?.get(1)
    } catch (_: IOException) {
        null  // worth trying the player call anyway; it may still answer
    }

    private fun player(id: String, client: Client, visitorData: String?): JSONObject {
        val clientJson = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("userAgent", client.userAgent)
            .put("hl", "sk")
            .put("gl", "SK")
        client.extra.forEach { (k, v) -> clientJson.put(k, v) }
        visitorData?.let { clientJson.put("visitorData", it) }

        val body = JSONObject()
            .put("context", JSONObject().put("client", clientJson))
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .toString()

        val conn = (URL(PLAYER_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", client.userAgent)
            setRequestProperty("X-YouTube-Client-Name", client.contextName.toString())
            setRequestProperty("X-YouTube-Client-Version", client.version)
            visitorData?.let { setRequestProperty("X-Goog-Visitor-Id", it) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String, userAgent: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

data class Stream(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val bytes: Long,
    val bitrate: Int,
    val height: Int,
    val label: String?,
    val userAgent: String,
)

data class Media(
    val title: String,
    val author: String,
    val seconds: Int,
    val audio: Stream,
    val videos: List<Stream>,
)

data class PlaylistItem(val videoId: String, val title: String)

data class Playlist(val title: String, val items: List<PlaylistItem>)
