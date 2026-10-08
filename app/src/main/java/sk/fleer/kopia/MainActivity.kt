package sk.fleer.kopia

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
                val saved = withContext(Dispatchers.IO) { download(url, wantVideo) }
                progress.progress = 100
                status.text = "Hotovo!\n$saved"
            } catch (e: Exception) {
                status.text = e.message ?: e.toString()
            } finally {
                button.isEnabled = true
            }
        }
    }

    private suspend fun download(url: String, wantVideo: Boolean): String {
        val media = Youtube.resolve(url, wantVideo)
        report("Sťahujem: ${media.title}")

        val work = File(cacheDir, "work").apply { mkdirs() }
        val audioFile = File(work, "audio.m4a")
        val videoFile = File(work, "video.mp4")
        val muxed = File(work, "muxed.mp4")
        listOf(audioFile, videoFile, muxed).forEach { it.delete() }

        try {
            // Progress is weighted by byte count across both streams, so the bar
            // reflects actual work rather than jumping between two phases.
            val totalBytes = media.audio.bytes + (media.video?.bytes ?: 0)
            var done = 0L

            Downloader.fetch(media.audio, audioFile) { got, _ -> publish(done + got, totalBytes) }
            done += media.audio.bytes

            val asVideo = wantVideo && media.video != null
            val result: File
            if (asVideo) {
                Downloader.fetch(media.video!!, videoFile) { got, _ ->
                    publish(done + got, totalBytes)
                }
                report("Spájam...")
                Muxer.combine(videoFile, audioFile, muxed)
                result = muxed
            } else {
                // Already a valid M4A as downloaded - nothing to combine.
                result = audioFile
            }

            report("Ukladám...")
            return Library.publish(this, result, media, asVideo)
        } finally {
            listOf(audioFile, videoFile, muxed).forEach { it.delete() }
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
