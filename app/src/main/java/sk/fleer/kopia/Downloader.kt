package sk.fleer.kopia

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Chunked Range downloads, several at once.
 *
 * Not one long GET: measured on a desktop, a single continuous connection to
 * googlevideo gets throttled and stalls - it died at 95% of a 2.7MB file. Asking
 * for a megabyte at a time avoids that.
 *
 * Four requests in flight: measured with this code on the user's network
 * (32 MiB of a 1080p60 stream, two rounds), 1 -> 2.1 MB/s, 2 -> 3.0, 4 -> 3.5,
 * 8 -> 3.7 MB/s. Four gets nearly all of it without leaning on YouTube harder
 * than needed. Chunks finish out of order, so each is written at its own offset.
 */
object Downloader {

    private const val CHUNK = 1L shl 20
    private const val ATTEMPTS = 4
    private const val PARALLEL = 4

    /** The user cancelled; not a failure, so it is kept apart from IOException. */
    class Cancelled : Exception("Zrušené.")

    fun fetch(
        stream: Stream,
        dest: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        dest.delete()
        RandomAccessFile(dest, "rw").use { it.setLength(stream.bytes) }

        val ranges = (0 until stream.bytes step CHUNK).map { it to minOf(it + CHUNK, stream.bytes) - 1 }
        val next = AtomicInteger(0)
        val received = AtomicLong(0)
        val failure = AtomicReference<Throwable?>(null)

        val workers = List(minOf(PARALLEL, ranges.size)) {
            thread(name = "kopia-download-$it") {
                // One file handle per worker: writes go to disjoint regions.
                RandomAccessFile(dest, "rw").use { out ->
                    while (failure.get() == null) {
                        val i = next.getAndIncrement()
                        if (i >= ranges.size) break
                        if (isCancelled()) {
                            failure.compareAndSet(null, Cancelled())
                            break
                        }
                        val (from, to) = ranges[i]
                        val data = try {
                            chunk(stream, from, to)
                        } catch (t: Throwable) {
                            failure.compareAndSet(null, t)  // the first error wins; the rest stop
                            break
                        }
                        out.seek(from)
                        out.write(data)
                        onProgress(received.addAndGet(data.size.toLong()), stream.bytes)
                    }
                }
            }
        }
        workers.forEach { it.join() }
        failure.get()?.let { throw it }
        if (received.get() != stream.bytes) {
            throw IOException("Stiahnuté ${received.get()} z ${stream.bytes} bajtov.")
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
