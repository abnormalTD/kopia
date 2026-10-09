package sk.fleer.kopia

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The single source of truth for what is being downloaded.
 *
 * The service writes it, the screen only reads it. That split is the fix for a
 * real bug: when the download lived inside the activity, rotating the phone
 * recreated the activity, silently cancelled the rest of the playlist, and the
 * new screen restored the old progress bar - showing progress for work that was
 * no longer happening.
 */
object Downloads {

    data class State(
        val running: Boolean = false,
        val line: String = "",
        val percent: Int = 0,
        val failures: List<Pair<String, String>> = emptyList(),
    )

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable

    /** A message from the screen while it is still working out what to download. */
    fun say(line: String) = mutable.update { State(line = line) }

    internal fun set(state: State) {
        mutable.value = state
    }
}

/**
 * What the screen hands to the service: everything already decided, so the
 * service never needs to ask the user anything.
 */
data class Job(
    val videoIds: List<String>,
    val titles: List<String>,
    /** Playlist title, used as the album; null for a single video. */
    val album: String?,
    /** 0 means audio only; otherwise the preferred video height. */
    val height: Int,
) {
    val isVideo get() = height > 0

    fun into(intent: Intent): Intent = intent
        .putStringArrayListExtra(IDS, ArrayList(videoIds))
        .putStringArrayListExtra(TITLES, ArrayList(titles))
        .putExtra(ALBUM, album)
        .putExtra(HEIGHT, height)

    companion object {
        private const val IDS = "ids"
        private const val TITLES = "titles"
        private const val ALBUM = "album"
        private const val HEIGHT = "height"

        fun from(intent: Intent): Job? {
            val ids = intent.getStringArrayListExtra(IDS) ?: return null
            if (ids.isEmpty()) return null
            val titles = intent.getStringArrayListExtra(TITLES) ?: arrayListOf()
            return Job(ids, titles, intent.getStringExtra(ALBUM), intent.getIntExtra(HEIGHT, 0))
        }
    }
}
