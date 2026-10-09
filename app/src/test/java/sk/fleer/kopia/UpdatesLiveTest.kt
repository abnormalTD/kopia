package sk.fleer.kopia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.URL

/** Checks the published release the update button would offer. Run with KOPIA_LIVE=1. */
class UpdatesLiveTest {

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getenv("KOPIA_LIVE") != null)

    @Test
    fun latestReleaseOffersADownloadableApk() {
        val release = Updates.latest()
        assertNotNull("no release with an APK on ${Updates.REPO}", release)
        println("latest: ${release!!.version} -> ${release.apkUrl}")
        assertTrue(release.apkUrl.endsWith(".apk"))

        val bytes = URL(release.apkUrl).openStream().use { it.readBytes() }
        println("downloaded ${bytes.size} B")
        // An APK is a zip archive: it must start with "PK".
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
    }

    @Test
    fun theSameVersionIsNotOfferedAsAnUpdate() {
        val release = Updates.latest() ?: return
        assertFalse(Updates.isNewer(release.version, release.version.removePrefix("v")))
    }
}
