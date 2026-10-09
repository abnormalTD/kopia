package sk.fleer.kopia

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    /**
     * The job travels to the service in memory, not inside the Intent. Both run
     * in this one process, and an Intent has a hard size limit: a long playlist's
     * IDs and titles would end in TransactionTooLargeException.
     */
    @Volatile
    private var pending: Job? = null

    /** A message from the screen while it is still working out what to download. */
    fun say(line: String) {
        mutable.value = State(line = line)
    }

    /**
     * Marks the download as running right away, so the button cannot start a
     * second one in the moment before the service picks this one up.
     */
    fun hand(job: Job) {
        pending = job
        mutable.value = State(running = true, line = "Pripravujem...")
    }

    internal fun take(): Job? = pending.also { pending = null }

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
}
