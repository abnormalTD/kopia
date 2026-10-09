package sk.fleer.kopia

import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub for the newest published release.
 *
 * The old app compared itself against a separate version.txt and then sent the
 * user to the releases page to find the file. Here the release *is* the source
 * of truth - its tag is the version and its .apk asset is the download - so the
 * two can never disagree, and one tap fetches the right file.
 */
object Updates {

    const val REPO = "abnormalTD/kopia"

    data class Release(val version: String, val apkUrl: String)

    /** The newest release with an APK attached, or null if there is none yet. */
    fun latest(): Release? {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Kopia")
        }
        try {
            val json = try {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } catch (_: FileNotFoundException) {
                return null  // 404: nothing published yet
            }
            val version = json.optString("tag_name").ifBlank { return null }
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    return Release(version, asset.optString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Numeric, part by part: "1.10" is newer than "1.9", and "v1.0" equals "1.0". */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.', '-')
            .map { it.toIntOrNull() ?: 0 }
}
