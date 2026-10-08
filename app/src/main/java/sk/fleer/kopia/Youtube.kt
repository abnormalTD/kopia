package sk.fleer.kopia

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
 * player to do it. Those are the clients used below.
 *
 * Measured 2026-10-08: ANDROID and IOS have gone SABR-only too (they answer OK
 * but every format comes back without a url), so only these two are left. Expect
 * that to keep shrinking.
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

    private val clients = listOf(
        Client(
            name = "VISIONOS", version = "1.02", contextName = 101,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            extra = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "RealityDevice17,1",
                "osName" to "visionOS",
                "osVersion" to "26.5.23O471",
            ),
        ),
        Client(
            name = "ANDROID_VR", version = "1.65.10", contextName = 28,
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            extra = mapOf(
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "androidSdkVersion" to 32,
                "osName" to "Android",
                "osVersion" to "12L",
            ),
        ),
    )

    class NotAvailable(message: String) : IOException(message)

    fun videoId(input: String): String {
        val text = input.trim()
        Regex("""(?:v=|/shorts/|youtu\.be/|/embed/|/live/)([A-Za-z0-9_-]{11})""")
            .find(text)?.let { return it.groupValues[1] }
        if (text.matches(Regex("""[A-Za-z0-9_-]{11}"""))) return text
        throw NotAvailable("V odkaze som nenašiel ID videa.")
    }

    fun resolve(url: String): Media {
        val id = videoId(url)
        // A bare player request gets "Sign in to confirm you're not a bot";
        // passing the visitorData from the watch page is what makes it pass.
        val visitorData = visitorData(id)
        var lastReason: String? = null

        for (client in clients) {
            val response = player(id, client, visitorData)
            val status = response.optJSONObject("playabilityStatus")
            val state = status?.optString("status")
            if (state != "OK") {
                lastReason = status?.optString("reason").orEmpty().ifBlank { state }
                continue
            }
            parse(response, client.userAgent)?.let { return it }
        }
        throw NotAvailable(lastReason?.takeIf { it.isNotBlank() } ?: "Video sa nedá stiahnuť.")
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
