package sk.fleer.kopia

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Runs downloads independently of the screen, so rotating the phone, locking it
 * or leaving the app no longer kills a playlist halfway through.
 *
 * A foreground service of type dataSync. Android 15 caps that type at about six
 * hours per day, which is far more than any download here takes.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notifications: NotificationManager
    private var lastNotifiedPercent = -1
    private var active: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sťahovanie", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must follow every startForegroundService, even one we
        // are about to ignore, or Android kills the app.
        ServiceCompat.startForeground(
            this, ONGOING_ID, progressNotification("Pripravujem...", 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        // The screen already marks the shared state as running when it hands over
        // a job, so "busy" has to be judged by this service's own work.
        if (active?.isActive == true) return START_NOT_STICKY
        val job = Downloads.take()
        if (job == null) {
            Downloads.say("")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        lastNotifiedPercent = -1
        active = scope.launch {
            try {
                run(job)
            } catch (e: Exception) {
                // Never leave the state stuck on "running": the screen keeps its
                // button disabled for as long as it says so.
                Downloads.set(Downloads.State(line = e.message ?: e.toString()))
            } finally {
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun run(job: Job) {
        val count = job.videoIds.size
        val failures = mutableListOf<Pair<String, String>>()
        var saved = 0
        var already = 0
        var lastOutcome: Outcome? = null

        for ((index, id) in job.videoIds.withIndex()) {
            val number = index + 1
            val known = job.titles.getOrNull(index)?.ifBlank { null }
            val prefix = if (count > 1) "$number/$count: " else "Sťahujem: "
            update(prefix + (known ?: "..."), index, count, 0.0)

            try {
                val outcome = downloadTrack(id, job, prefix, index, count)
                if (outcome is Outcome.Saved) saved++ else already++
                lastOutcome = outcome
            } catch (e: Exception) {
                // One unavailable track should not abandon the others - but keep
                // the reason, or nobody can tell a refusal from a dropped line.
                failures += (known ?: id) to (e.message ?: e.javaClass.simpleName)
            }
        }

        val folder = if (job.isVideo) "Movies/Kopia" else "Music/Kopia"
        val (line, title, text) = when {
            count == 1 && lastOutcome is Outcome.Saved ->
                Triple("Hotovo!\n${lastOutcome.path}", "Hotovo", lastOutcome.path)
            count == 1 && lastOutcome is Outcome.Already ->
                Triple("Túto už máš:\n${lastOutcome.path}", "Už ju máš", lastOutcome.path)
            count == 1 ->
                Triple(failures.first().second, "Nepodarilo sa", failures.first().second)
            saved == 0 && failures.isEmpty() ->
                Triple("Nič nové - všetkých $already už máš.", "Nič nové", folder)
            else -> Triple(
                buildString {
                    append("Hotovo! Nových: $saved")
                    if (already > 0) append(", už si mal: $already")
                    append("\n$folder")
                    if (failures.isNotEmpty()) append("\n${failures.size} sa nepodarilo - klepni sem pre dôvod.")
                },
                "Hotovo, nových $saved",
                folder,
            )
        }
        Downloads.set(Downloads.State(running = false, line = line, percent = 100, failures = failures))
        notifications.notify(DONE_ID, doneNotification(title, text))
    }

    private sealed interface Outcome {
        val path: String
        class Saved(override val path: String) : Outcome
        class Already(override val path: String) : Outcome
    }

    private fun downloadTrack(id: String, job: Job, prefix: String, index: Int, count: Int): Outcome {
        val key = Library.key(id, job.height)
        Library.known(this, key)?.let { return Outcome.Already(it) }

        var media = Youtube.resolve(id)
        update(prefix + media.title, index, count, 0.0)
        val track = if (count > 1) index + 1 else null
        val slot = when (val claim = Library.claim(this, media, job.isVideo, job.album, track)) {
            is Library.Claim.Taken -> return Outcome.Already(claim.path)
            is Library.Claim.Free -> claim.slot
        }

        try {
            var swaps = 0
            while (true) {
                val video = if (job.isVideo) pickVideo(media, job.height) else null
                try {
                    val path = fetch(media, video, slot, key) { fraction ->
                        update(prefix + media.title, index, count, fraction)
                    }
                    return Outcome.Saved(path)
                } catch (e: Downloader.Refused) {
                    if (++swaps > IDENTITY_SWAPS) throw e
                    Youtube.forgetIdentity()
                    update(prefix + media.title + " (nový pokus)", index, count, 0.0)
                    media = Youtube.resolve(id)
                }
            }
        } catch (e: Exception) {
            Library.abandon(this, slot)  // never leave a half-written entry behind
            throw e
        }
    }

    /** The exact height if this video has it, otherwise the nearest one. */
    private fun pickVideo(media: Media, height: Int): Stream {
        if (media.videos.isEmpty()) throw Youtube.NotAvailable("Toto video sa nedá stiahnuť ako video.")
        return media.videos.minBy { abs(it.height - height) }
    }

    private fun fetch(
        media: Media,
        video: Stream?,
        slot: Library.Slot,
        key: String,
        onProgress: (Double) -> Unit,
    ): String {
        val work = File(cacheDir, "work").apply { mkdirs() }
        val audioFile = File(work, "audio.m4a")
        val videoFile = File(work, "video.mp4")
        val muxed = File(work, "muxed.mp4")
        val temps = listOf(audioFile, videoFile, muxed)
        temps.forEach { it.delete() }

        try {
            val total = (media.audio.bytes + (video?.bytes ?: 0)).toDouble()
            Downloader.fetch(media.audio, audioFile) { got, _ -> onProgress(got / total) }

            val result = if (video != null) {
                Downloader.fetch(video, videoFile) { got, _ ->
                    onProgress((media.audio.bytes + got) / total)
                }
                Muxer.combine(videoFile, audioFile, muxed)
                muxed
            } else {
                // Already a valid M4A as downloaded - nothing to combine.
                audioFile
            }
            return Library.fill(this, slot, result, key)
        } finally {
            temps.forEach { it.delete() }
        }
    }

    /** Overall progress counts finished tracks plus the fraction of the current one. */
    private fun update(line: String, index: Int, count: Int, fraction: Double) {
        val percent = (((index + fraction.coerceIn(0.0, 1.0)) / count) * 100).toInt()
        Downloads.set(Downloads.State(running = true, line = line, percent = percent))
        // The system throttles notification updates; only post real changes.
        if (percent != lastNotifiedPercent) {
            lastNotifiedPercent = percent
            notifications.notify(ONGOING_ID, progressNotification(line, percent))
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(text: String, percent: Int) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Kópia sťahuje")
            .setContentText(text)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun doneNotification(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    companion object {
        private const val CHANNEL = "downloads"
        private const val ONGOING_ID = 1
        private const val DONE_ID = 2

        /** At ~1 bad identity in 9, three fresh draws all failing is ~1 in 700. */
        private const val IDENTITY_SWAPS = 3
    }
}
