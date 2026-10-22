package sk.fleer.kopia

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/** Runs on every build: a synthetic MP4 whose moov comes first, so tagging must move chunk offsets. */
class Mp4TagsTest {

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
    }

    private fun stco(vararg offsets: Int): ByteArray {
        val b = ByteBuffer.allocate(8 + 4 * offsets.size).putInt(0).putInt(offsets.size)
        offsets.forEach { b.putInt(it) }
        return box("stco", b.array())
    }

    /** ftyp + moov(trak with stco pointing at two chunks) + mdat holding those chunks. */
    private fun sample(): Pair<ByteArray, ByteArray> {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ByteArray(4))
        val chunks = "CHUNK-ONE".toByteArray() + "CHUNK-TWO".toByteArray()
        // build once with placeholder offsets to learn where mdat's payload lands
        fun build(a: Int, b: Int) = ftyp + box("moov", box("trak", box("mdia", box("minf", box("stbl", stco(a, b)))))) +
            box("mdat", chunks)
        val probe = build(0, 0)
        val payload = probe.size - chunks.size
        return build(payload, payload + 9) to chunks
    }

    private fun chunkOffsets(file: ByteArray): List<Int> {
        val at = String(file, Charsets.ISO_8859_1).indexOf("stco")
        val b = ByteBuffer.wrap(file)
        return List(b.getInt(at + 8)) { b.getInt(at + 12 + it * 4) }
    }

    @Test
    fun movesChunkOffsetsWhenMoovGrows() {
        val (bytes, chunks) = sample()
        val file = File.createTempFile("mp4tags", ".m4a").apply { writeBytes(bytes); deleteOnExit() }

        assertTrue(Mp4Tags.write(file, Mp4Tags.Tags("Názov", "Interpret", "Album", 3, 9)))

        val tagged = file.readBytes()
        val text = String(tagged, Charsets.ISO_8859_1)
        assertTrue(text.contains("©alb") && text.contains("trkn"))
        assertTrue(String(tagged, Charsets.UTF_8).contains("Názov"))
        val (first, second) = chunkOffsets(tagged)
        // the offsets must still point at the same bytes
        assertArrayEquals("CHUNK-ONE".toByteArray(), tagged.copyOfRange(first, first + 9))
        assertArrayEquals("CHUNK-TWO".toByteArray(), tagged.copyOfRange(second, second + 9))
        assertEquals(chunks.size, tagged.size - (text.lastIndexOf("mdat") + 4))
    }

    @Test
    fun replacesAnExistingTagBlockInsteadOfAddingASecond() {
        val (bytes, _) = sample()
        val file = File.createTempFile("mp4tags", ".m4a").apply { writeBytes(bytes); deleteOnExit() }
        Mp4Tags.write(file, Mp4Tags.Tags("Prvý"))
        Mp4Tags.write(file, Mp4Tags.Tags("Druhý"))
        val text = String(file.readBytes(), Charsets.UTF_8)
        assertEquals(1, Regex("udta").findAll(text).count())
        assertTrue(text.contains("Druhý") && !text.contains("Prvý"))
    }

    @Test
    fun leavesAFileItDoesNotUnderstandUntouched() {
        val junk = ByteArray(64) { it.toByte() }
        val file = File.createTempFile("mp4tags", ".m4a").apply { writeBytes(junk); deleteOnExit() }
        assertFalse(Mp4Tags.write(file, Mp4Tags.Tags("x")))
        assertArrayEquals(junk, file.readBytes())
    }
}
