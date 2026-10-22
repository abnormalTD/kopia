package sk.fleer.kopia

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFolderTest {
    @Test fun singlesStayInTheBaseFolder() {
        assertEquals("Music/Kopia", Library.folderFor(false, null))
        assertEquals("Movies/Kopia", Library.folderFor(true, null))
    }
    @Test fun aPlaylistGetsItsOwnFolder() {
        assertEquals("Music/Kopia/Spain", Library.folderFor(false, "Spain"))
        assertEquals("Movies/Kopia/Detektívka", Library.folderFor(true, "Detektívka"))
    }
    @Test fun unsafeNamesAreCleanedUp() {
        assertEquals("Music/Kopia/AC DC Best", Library.folderFor(false, "AC/DC: Best?"))
        assertEquals("Music/Kopia/hidden", Library.folderFor(false, "..hidden.."))
        assertEquals("Music/Kopia", Library.folderFor(false, " .. "))
        assertEquals("Music/Kopia/a b", Library.folderFor(false, "a   b"))
    }
}
