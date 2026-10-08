package sk.fleer.kopia

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var link: EditText
    private lateinit var videoSwitch: Switch
    private lateinit var button: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        link = findViewById(R.id.link)
        videoSwitch = findViewById(R.id.videoSwitch)
        button = findViewById(R.id.download)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        videoSwitch.setOnCheckedChangeListener { _, video ->
            button.text = if (video) "STIAHNUŤ VIDEO" else "STIAHNUŤ HUDBU"
        }
        button.setOnClickListener { start() }

        takeSharedLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSharedLink(intent)
    }

    /** Fills the field when a link arrives from another app's share sheet. */
    private fun takeSharedLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        // Share text is often "Title https://youtu.be/... " rather than a bare URL.
        val url = Regex("""https?://\S+""").find(shared)?.value ?: shared
        link.setText(url.trim())
        status.text = "Odkaz prevzatý zo zdieľania."
    }

    private fun start() {
        val url = link.text.toString().trim()
        if (url.isEmpty()) {
            status.text = "Najprv vlož odkaz."
            return
        }
        val wantVideo = videoSwitch.isChecked

        button.isEnabled = false
        progress.progress = 0
        status.text = "Zisťujem..."

        lifecycleScope.launch {
            try {
                val media = withContext(Dispatchers.IO) { Youtube.resolve(url) }

                var chosen: Stream? = null
                if (wantVideo) {
                    if (media.videos.isEmpty()) {
                        throw Youtube.NotAvailable("Toto video sa nedá stiahnuť ako video.")
                    }
                    chosen = askQuality(media) ?: run {
                        status.text = "Zrušené."
                        return@launch
                    }
                }

                val saved = withContext(Dispatchers.IO) { fetch(media, chosen) }
                progress.progress = 100
                status.text = "Hotovo!\n$saved"
            } catch (e: Exception) {
                status.text = e.message ?: e.toString()
            } finally {
                button.isEnabled = true
            }
        }
    }

    /** Shows the real download size per resolution - audio is always included. */
    private suspend fun askQuality(media: Media): Stream? = suspendCancellableCoroutine { cont ->
        val labels = media.videos.map { video ->
            val mb = (video.bytes + media.audio.bytes) / 1024.0 / 1024.0
            "${video.label ?: "${video.height}p"}  ·  ${"%.1f".format(mb)} MB"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Vyber kvalitu")
            .setItems(labels) { _, index -> cont.resume(media.videos[index]) }
            .setOnCancelListener { cont.resume(null) }
            .create()
        cont.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }

    private fun fetch(media: Media, video: Stream?): String {
        val work = File(cacheDir, "work").apply { mkdirs() }
        val audioFile = File(work, "audio.m4a")
        val videoFile = File(work, "video.mp4")
        val muxed = File(work, "muxed.mp4")
        val temps = listOf(audioFile, videoFile, muxed)
        temps.forEach { it.delete() }

        try {
            // Progress is weighted by byte count across both streams, so the bar
            // reflects actual work rather than jumping between two phases.
            val totalBytes = media.audio.bytes + (video?.bytes ?: 0)
            var done = 0L

            report("Sťahujem: ${media.title}")
            Downloader.fetch(media.audio, audioFile) { got, _ -> publish(done + got, totalBytes) }
            done += media.audio.bytes

            val result: File
            if (video != null) {
                Downloader.fetch(video, videoFile) { got, _ -> publish(done + got, totalBytes) }
                report("Spájam...")
                Muxer.combine(videoFile, audioFile, muxed)
                result = muxed
            } else {
                // Already a valid M4A as downloaded - nothing to combine.
                result = audioFile
            }

            report("Ukladám...")
            return Library.publish(this, result, media, video != null)
        } finally {
            temps.forEach { it.delete() }
        }
    }

    private fun publish(done: Long, total: Long) {
        if (total <= 0) return
        val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
        runOnUiThread { progress.progress = percent }
    }

    private fun report(text: String) {
        runOnUiThread { status.text = text }
    }
}
