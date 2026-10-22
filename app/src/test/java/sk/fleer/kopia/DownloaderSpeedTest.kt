package sk.fleer.kopia

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Measures whether parallel range requests beat the app's one-at-a-time
 * Downloader, using the app's own code and HttpURLConnection (which keeps
 * connections alive, unlike the Python probe that suggested a 1.9x gain).
 * Live, slow and data-hungry (~200 MB per run - ask before running it on
 * mobile data): KOPIA_LIVE=1 KOPIA_SPEED=1.
 */
class DownloaderSpeedTest {

    private val mb = 1L shl 20
    private val span = 32 * mb

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getenv("KOPIA_LIVE") != null && System.getenv("KOPIA_SPEED") != null)

    private fun stream(): Stream {
        val media = Youtube.resolve("_8a1-yB8i_4")  // 1080p60 video from the user's playlist, ~51 MB
        val video = media.videos.maxBy { it.bytes }
        println("stream: ${media.title}, ${video.label}, ${video.bytes / mb} MB")
        return video.copy(bytes = span)
    }

    private fun sequential(s: Stream): Double {
        val dest = File.createTempFile("seq", ".bin").apply { delete(); deleteOnExit() }
        val t0 = System.nanoTime()
        Downloader.fetch(s, dest) { _, _ -> }
        val secs = (System.nanoTime() - t0) / 1e9
        check(dest.length() == span)
        return span / mb / secs
    }

    private fun parallel(s: Stream, workers: Int): Double {
        val got = AtomicLong()
        val pool = Executors.newFixedThreadPool(workers)
        val t0 = System.nanoTime()
        for (start in 0 until span step mb) {
            pool.submit {
                val conn = (URL(s.url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", s.userAgent)
                    setRequestProperty("Range", "bytes=$start-${start + mb - 1}")
                }
                try {
                    check(conn.responseCode == 206) { "HTTP ${conn.responseCode}" }
                    got.addAndGet(conn.inputStream.use { it.readBytes().size.toLong() })
                } finally {
                    conn.disconnect()
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.MINUTES)
        val secs = (System.nanoTime() - t0) / 1e9
        check(got.get() == span) { "got ${got.get()}" }
        return span / mb / secs
    }

    /** The parallel download must be byte-identical to a plain one-chunk-at-a-time read. */
    @Test
    fun parallelResultMatchesSequentialBytes() {
        val s = stream()
        val dest = File.createTempFile("par", ".bin").apply { deleteOnExit() }
        val t0 = System.nanoTime()
        Downloader.fetch(s, dest) { _, _ -> }
        val speed = span / mb / ((System.nanoTime() - t0) / 1e9)
        val reference = java.io.ByteArrayOutputStream()
        for (start in 0 until span step mb) {
            val conn = (URL(s.url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", s.userAgent)
                setRequestProperty("Range", "bytes=$start-${start + mb - 1}")
            }
            conn.inputStream.use { reference.write(it.readBytes()) }
        }
        val sha = { b: ByteArray -> java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } }
        val a = sha(dest.readBytes()); val b = sha(reference.toByteArray())
        println("Downloader (parallel): ${"%.1f".format(speed)} MB/s, sha ${a.take(16)} vs reference ${b.take(16)}")
        check(a == b) { "parallel download differs from sequential" }
    }

    /** The shipped Downloader against the old one-chunk-at-a-time way, alternating, three rounds. */
    @Test
    fun compare() {
        val s = stream()
        val old = mutableListOf<Double>()
        val new = mutableListOf<Double>()
        repeat(3) { round ->
            for (which in if (round % 2 == 0) listOf("old", "new") else listOf("new", "old")) {
                val speed = if (which == "old") parallel(s, 1) else {
                    val dest = File.createTempFile("new", ".bin").apply { deleteOnExit() }
                    val t0 = System.nanoTime()
                    Downloader.fetch(s, dest) { _, _ -> }
                    span / mb / ((System.nanoTime() - t0) / 1e9)
                }
                (if (which == "old") old else new) += speed
                println("  kolo ${round + 1} $which: ${"%.1f".format(speed)} MB/s")
            }
        }
        println("priemer postaru: ${"%.1f".format(old.average())} MB/s, Kópia teraz: ${"%.1f".format(new.average())} MB/s, " +
            "${"%.2f".format(new.average() / old.average())}x")
    }
}
