package sk.fleer.kopia

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Chunked Range downloads.
 *
 * Not one long GET: measured on a desktop, a single continuous connection to
 * googlevideo gets throttled and stalls - it died at 95% of a 2.7MB file. Asking
 * for a megabyte at a time avoids that, and makes resuming free.
 */
object Downloader {

    private const val CHUNK = 1L shl 20
    private const val ATTEMPTS = 4

    fun fetch(stream: Stream, dest: File, onProgress: (Long, Long) -> Unit) {
        val from = if (dest.isFile) dest.length() else 0L
        if (from >= stream.bytes) return

        dest.parentFile?.mkdirs()
        java.io.FileOutputStream(dest, from > 0).use { out ->
            var at = from
            while (at < stream.bytes) {
                val last = minOf(at + CHUNK, stream.bytes) - 1
                out.write(chunk(stream, at, last))
                at = last + 1
                onProgress(at, stream.bytes)
            }
        }
        if (dest.length() != stream.bytes) {
            throw IOException("Stiahnuté ${dest.length()} z ${stream.bytes} bajtov.")
        }
    }

    /** The server said no. Retrying will not change its mind. */
    class Refused(message: String) : IOException(message)

    private fun chunk(stream: Stream, from: Long, to: Long): ByteArray {
        var lastProblem = ""
        for (attempt in 1..ATTEMPTS) {
            val conn = (URL(stream.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", stream.userAgent)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Range", "bytes=$from-$to")
            }
            try {
                val code = conn.responseCode
                when {
                    code == HttpURLConnection.HTTP_PARTIAL ->
                        return conn.inputStream.use { it.readBytes() }
                    code == 403 || code == 404 || code == 410 ->
                        throw Refused("Server odmietol sťahovanie (HTTP $code) na bajte $from.")
                    else -> lastProblem = "HTTP $code"
                }
            } catch (e: Refused) {
                throw e
            } catch (e: IOException) {
                lastProblem = e.message ?: e.javaClass.simpleName
            } finally {
                conn.disconnect()
            }
            if (attempt < ATTEMPTS) Thread.sleep(1000L * attempt)
        }
        throw IOException("Sťahovanie zlyhalo na bajte $from ($lastProblem).")
    }
}
