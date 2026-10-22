package sk.fleer.kopia

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The screen only decides *what* to download - asking about playlists and
 * quality - and then hands a finished [Job] to [DownloadService]. Everything it
 * shows comes from [Downloads.state], so after a rotation it simply shows the
 * truth again instead of a restored, frozen progress bar.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var link: EditText
    private lateinit var videoSwitch: Switch
    private lateinit var button: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    /** True while this screen is still asking questions before a download starts. */
    private var asking = false

    // Downloads work without it; the user just would not see the notification.
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        keepClearOfSystemBars()

        link = findViewById(R.id.link)
        videoSwitch = findViewById(R.id.videoSwitch)
        button = findViewById(R.id.download)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        videoSwitch.setOnCheckedChangeListener { _, _ -> render(Downloads.state.value) }
        button.setOnClickListener {
            if (Downloads.state.value.running) DownloadService.cancel(this) else start()
        }
        status.setOnClickListener {
            if (Downloads.state.value.failures.isNotEmpty()) showFailures()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Downloads.state.collect { render(it) }
            }
        }

        takeSharedLink(intent)
        if (savedInstanceState == null) checkForUpdate()
    }

    /**
     * Quietly asks GitHub whether a newer release exists; any failure just means
     * no button. One tap opens the APK itself, not a page to search through.
     */
    private fun checkForUpdate() {
        val update = findViewById<Button>(R.id.update)
        val installed = packageManager.getPackageInfo(packageName, 0).versionName ?: return
        lifecycleScope.launch {
            val release = runCatching { withContext(Dispatchers.IO) { Updates.latest() } }.getOrNull()
                ?: return@launch
            if (!Updates.isNewer(release.version, installed)) return@launch
            update.text = "NOVÁ VERZIA ${release.version.removePrefix("v")} – STIAHNUŤ"
            update.visibility = View.VISIBLE
            update.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)))
            }
        }
    }

    /**
     * From Android 15, apps targeting it are drawn edge to edge: the layout runs
     * under the status bar unless it pads itself. Seen on the user's Pixel 7 -
     * the title sat under the clock and battery icons.
     */
    private fun keepClearOfSystemBars() {
        val root = findViewById<View>(R.id.root)
        val base = root.paddingTop  // the layout's own 20dp, on every side
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            insets
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSharedLink(intent)
    }

    private fun render(state: Downloads.State) {
        status.text = state.line
        progress.visibility = if (state.running) View.VISIBLE else View.INVISIBLE
        progress.progress = state.percent
        // While a download runs, the (otherwise idle) download button is Cancel.
        button.text = when {
            state.running -> "ZRUŠIŤ"
            videoSwitch.isChecked -> "STIAHNUŤ VIDEO"
            else -> "STIAHNUŤ HUDBU"
        }
        button.isEnabled = if (state.running) !state.cancelling else !asking
    }

    /** Fills the field when a link arrives from another app's share sheet. */
    private fun takeSharedLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        // Share text is often "Title https://youtu.be/... " rather than a bare URL.
        val url = Regex("""https?://\S+""").find(shared)?.value ?: shared
        link.setText(url.trim())
        if (!Downloads.state.value.running) Downloads.say("Odkaz prevzatý zo zdieľania.")
    }

    private fun start() {
        val url = link.text.toString().trim()
        if (url.isEmpty()) {
            Downloads.say("Najprv vlož odkaz.")
            return
        }
        val wantVideo = videoSwitch.isChecked
        requestNotificationsOnce()

        asking = true
        button.isEnabled = false
        Downloads.say("Zisťujem...")

        lifecycleScope.launch {
            try {
                decide(url, wantVideo)?.let { launchDownload(it) }
                    ?: Downloads.say("Zrušené.")
            } catch (e: CancellationException) {
                // The screen went away mid-dialog (e.g. rotation). Nothing was
                // started; leave a clean slate rather than a "cancelled" message.
                Downloads.say("")
                throw e
            } catch (e: Exception) {
                Downloads.say(e.message ?: e.toString())
            } finally {
                asking = false
                render(Downloads.state.value)
            }
        }
    }

    /** Works out the job, or null if the user backed out of a dialog. */
    private suspend fun decide(url: String, wantVideo: Boolean): Job? {
        val singleId = runCatching { Youtube.videoId(url) }.getOrNull()
        val playlist = Youtube.playlistId(url)?.let { id ->
            runCatching { withContext(Dispatchers.IO) { Youtube.playlist(id) } }.getOrNull()
        }

        val wholePlaylist = when {
            playlist == null || playlist.items.size < 2 -> false
            // A shared playlist link carries no video of its own, so "only this
            // one" would have nothing to point at.
            singleId == null -> true
            else -> askWholePlaylist(playlist) ?: return null
        }

        if (wholePlaylist && playlist != null) {
            val height = if (wantVideo) {
                Downloads.say("Zisťujem dostupné kvality...")
                qualityFromFirstPlayable(playlist) ?: return null
            } else 0
            return Job(
                videoIds = playlist.items.map { it.videoId },
                titles = playlist.items.map { it.title },
                album = playlist.title,
                height = height,
            )
        }

        val id = singleId ?: throw Youtube.NotAvailable("V odkaze som nenašiel ID videa.")
        if (!wantVideo) return Job(listOf(id), emptyList(), album = null, height = 0)

        val media = withContext(Dispatchers.IO) { Youtube.resolve(id) }
        if (media.videos.isEmpty()) throw Youtube.NotAvailable("Toto video sa nedá stiahnuť ako video.")
        val chosen = askQuality(media) ?: return null
        return Job(listOf(id), listOf(media.title), album = null, height = chosen.height)
    }

    /**
     * Quality is asked once per playlist, using the first track that resolves.
     * The service then takes the nearest height for every other track.
     */
    private suspend fun qualityFromFirstPlayable(playlist: Playlist): Int? {
        for (item in playlist.items.take(3)) {
            val media = runCatching { withContext(Dispatchers.IO) { Youtube.resolve(item.videoId) } }
                .getOrNull() ?: continue
            if (media.videos.isEmpty()) continue
            return askQuality(media)?.height
        }
        return 1080  // nothing to show; aim high, nearest available wins per track
    }

    private fun launchDownload(job: Job) {
        Downloads.hand(job)
        ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java))
    }

    private fun requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** true = all, false = just this video, null = backed out. */
    private suspend fun askWholePlaylist(playlist: Playlist): Boolean? =
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(this)
                .setTitle(playlist.title)
                .setMessage(
                    if (playlist.incomplete) {
                        "Načítal som ${playlist.items.size} videí, playlist má ale viac. " +
                            "Stiahne sa len týchto ${playlist.items.size}."
                    } else {
                        "Playlist má ${playlist.items.size} videí."
                    }
                )
                .setPositiveButton("Stiahnuť všetky") { _, _ -> cont.resume(true) }
                .setNegativeButton("Len toto jedno") { _, _ -> cont.resume(false) }
                .setOnCancelListener { cont.resume(null) }
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
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

    private fun showFailures() {
        val text = Downloads.state.value.failures
            .joinToString("\n\n") { (title, reason) -> "• $title\n   $reason" }
        AlertDialog.Builder(this)
            .setTitle("Nepodarilo sa stiahnuť")
            .setMessage(text)
            .setPositiveButton("Zavrieť", null)
            .show()
    }
}
