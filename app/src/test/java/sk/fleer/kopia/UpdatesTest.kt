package sk.fleer.kopia

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {

    @Test
    fun comparesNumericallyNotAsText() {
        // As strings "1.10" < "1.9"; as versions it is the other way round.
        assertTrue(Updates.isNewer("1.10", "1.9"))
        assertFalse(Updates.isNewer("1.9", "1.10"))
    }

    @Test
    fun ignoresTheTagPrefixAndTrailingZeros() {
        assertFalse(Updates.isNewer("v1.0", "1.0"))
        assertFalse(Updates.isNewer("1.0.0", "1.0"))
        assertTrue(Updates.isNewer("v1.0.1", "1.0"))
    }

    @Test
    fun anOlderReleaseIsNotAnUpdate() {
        // e.g. running a locally built newer version than what is published
        assertFalse(Updates.isNewer("1.0", "1.1"))
    }
}
