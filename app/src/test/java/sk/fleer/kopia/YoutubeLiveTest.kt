package sk.fleer.kopia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the app's real extraction code against live YouTube - a canary for the
 * day YouTube changes something again. Off by default (it needs the network
 * and YouTube's cooperation); run with:
 *
 *     KOPIA_LIVE=1 ./gradlew testDebugUnitTest
 */
class YoutubeLiveTest {

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getenv("KOPIA_LIVE") != null)

    @Test
    fun singleVideoHasDirectStreams() {
        val media = Youtube.resolve("XYTozYWp-Tw")
        println("resolve: '${media.title}', audio ${media.audio.bytes} B, ${media.videos.size} H.264 sizes")
        assertTrue(media.audio.url.startsWith("https://"))
        assertTrue(media.videos.isNotEmpty())
    }

    @Test
    fun shortPlaylistIsReadWhole() {
        val p = Youtube.playlist("PLD-ZRtrm9GCE3eRfxgn2EBerng7Htln0B")
        println("short playlist: '${p.title}', ${p.items.size} items, incomplete=${p.incomplete}")
        // The user's own playlist: its length changes whenever they add a song,
        // so check what matters - it fits in the first page and is read whole.
        assertTrue(p.items.size in 1..96)
        assertFalse(p.incomplete)
    }

    @Test
    fun longPlaylistIsPagedAndCapped() {
        // A channel's uploads list: "UU" + the channel id without "UC".
        val p = Youtube.playlist("UUgwv23FVv3lqh567yagXfNg")
        println("long playlist: ${p.items.size} items, incomplete=${p.incomplete}")
        assertEquals(1000, p.items.size)
        assertTrue(p.incomplete)
        assertEquals(p.items.size, p.items.map { it.videoId }.toSet().size)
    }
}
