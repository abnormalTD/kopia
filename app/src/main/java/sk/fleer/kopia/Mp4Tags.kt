package sk.fleer.kopia

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Writes iTunes-style tags (title, artist, album, track number) into an MP4/M4A.
 *
 * MediaStore ignores ALBUM/ARTIST/TRACK given through ContentValues: once a file
 * is finished it re-reads them from the file itself - measured on the user's
 * Pixel 7, every song showed album "Kopia" (the folder), artist <unknown>, no
 * track. So the tags have to live in the file, in moov/udta/meta/ilst.
 *
 * Growing moov shifts everything after it. YouTube's audio is fragmented with
 * offsets relative to each moof (checked: tfhd default-base-is-moof, empty stco),
 * so that is safe; plain MP4s (MediaMuxer output) get their stco/co64 chunk
 * offsets moved by the same amount. Any layout this code is not sure about -
 * absolute fragment offsets, an mfra index, more than one moov - is left
 * untagged rather than risk a broken file.
 */
object Mp4Tags {

    data class Tags(
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val track: Int? = null,
        val trackCount: Int? = null,
    )

    /** Rewrites [file] with [tags]. Returns false, leaving the file untouched, if it would not be safe. */
    fun write(file: File, tags: Tags): Boolean {
        val top = RandomAccessFile(file, "r").use { topLevel(it) } ?: return false
        val moov = top.singleOrNull { it.type == "moov" } ?: return false
        if (top.any { it.type == "mfra" }) return false

        RandomAccessFile(file, "r").use { raf ->
            for (box in top) if (box.type == "moof" && hasAbsoluteFragmentOffsets(read(raf, box))) return false
        }

        val oldMoov = RandomAccessFile(file, "r").use { read(it, moov) }
        val children = parse(oldMoov, moov.header, oldMoov.size).filter { it.type != "udta" }
        val udta = box("udta", meta(tags))
        val newSize = 8 + children.sumOf { it.bytes.size } + udta.size
        val delta = (newSize - moov.size).toLong()
        val moovEnd = moov.offset + moov.size

        val body = ByteArrayOutputStream()
        for (child in children) {
            if (child.type == "trak" && !shiftChunkOffsets(child.bytes, moovEnd, delta)) return false
            body.write(child.bytes)
        }
        body.write(udta)
        val newMoov = box("moov", body.toByteArray())

        val temp = File(file.parentFile, file.name + ".tagging")
        RandomAccessFile(file, "r").use { src ->
            temp.outputStream().buffered(1 shl 16).use { out ->
                copy(src, 0, moov.offset, out)
                out.write(newMoov)
                copy(src, moovEnd, src.length() - moovEnd, out)
            }
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            return false
        }
        return true
    }

    // ---- reading ----------------------------------------------------------

    private class Box(val type: String, val offset: Long, val size: Long, val header: Int)

    private class Child(val type: String, val bytes: ByteArray)

    private fun topLevel(raf: RandomAccessFile): List<Box>? {
        val out = mutableListOf<Box>()
        var at = 0L
        val length = raf.length()
        while (at + 8 <= length) {
            raf.seek(at)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = typeOf(raf.readInt())
            var header = 8
            if (size == 1L) {
                size = raf.readLong()
                header = 16
            } else if (size == 0L) {
                size = length - at
            }
            if (size < header || at + size > length) return null
            out += Box(type, at, size, header)
            at += size
        }
        return if (at == length) out else null
    }

    private fun read(raf: RandomAccessFile, box: Box): ByteArray {
        require(box.size <= Int.MAX_VALUE)
        raf.seek(box.offset)
        return ByteArray(box.size.toInt()).also { raf.readFully(it) }
    }

    private fun parse(buf: ByteArray, from: Int, to: Int): List<Child> {
        val out = mutableListOf<Child>()
        var at = from
        while (at + 8 <= to) {
            val size = ByteBuffer.wrap(buf, at, 4).int
            if (size < 8 || at + size > to) break
            out += Child(typeOf(ByteBuffer.wrap(buf, at + 4, 4).int), buf.copyOfRange(at, at + size))
            at += size
        }
        return out
    }

    private fun typeOf(code: Int) = String(
        byteArrayOf((code shr 24).toByte(), (code shr 16).toByte(), (code shr 8).toByte(), code.toByte()),
        Charsets.ISO_8859_1,
    )

    /** A moof whose tfhd carries base-data-offset points at absolute file positions. */
    private fun hasAbsoluteFragmentOffsets(moof: ByteArray): Boolean {
        for (traf in parse(moof, 8, moof.size).filter { it.type == "traf" }) {
            for (tfhd in parse(traf.bytes, 8, traf.bytes.size).filter { it.type == "tfhd" }) {
                val flags = ByteBuffer.wrap(tfhd.bytes, 8, 4).int and 0xFFFFFF
                if (flags and 0x1 != 0) return true
            }
        }
        return false
    }

    /**
     * Moves every chunk offset that points past the old moov by [delta], inside
     * trak/mdia/minf/stbl. Works in place on [trak]. False if a 32-bit offset
     * would overflow.
     */
    private fun shiftChunkOffsets(trak: ByteArray, moovEnd: Long, delta: Long): Boolean {
        fun walk(buf: ByteArray, from: Int, to: Int): Boolean {
            var at = from
            while (at + 8 <= to) {
                val size = ByteBuffer.wrap(buf, at, 4).int
                if (size < 8 || at + size > to) return true
                when (typeOf(ByteBuffer.wrap(buf, at + 4, 4).int)) {
                    "mdia", "minf", "stbl" -> if (!walk(buf, at + 8, at + size)) return false
                    "stco" -> {
                        val b = ByteBuffer.wrap(buf)
                        val count = b.getInt(at + 12)
                        for (i in 0 until count) {
                            val pos = at + 16 + i * 4
                            val value = b.getInt(pos).toLong() and 0xFFFFFFFFL
                            if (value >= moovEnd) {
                                val moved = value + delta
                                if (moved > 0xFFFFFFFFL) return false
                                b.putInt(pos, moved.toInt())
                            }
                        }
                    }
                    "co64" -> {
                        val b = ByteBuffer.wrap(buf)
                        val count = b.getInt(at + 12)
                        for (i in 0 until count) {
                            val pos = at + 16 + i * 8
                            val value = b.getLong(pos)
                            if (value >= moovEnd) b.putLong(pos, value + delta)
                        }
                    }
                }
                at += size
            }
            return true
        }
        return walk(trak, 8, trak.size)
    }

    private fun copy(src: RandomAccessFile, from: Long, length: Long, out: java.io.OutputStream) {
        src.seek(from)
        val buffer = ByteArray(1 shl 16)
        var left = length
        while (left > 0) {
            val n = src.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            left -= n
        }
    }

    // ---- writing ----------------------------------------------------------

    private fun meta(tags: Tags): ByteArray {
        val ilst = ByteArrayOutputStream()
        ilst.write(text("©nam", tags.title))
        tags.artist?.let { ilst.write(text("©ART", it)) }
        tags.album?.let { ilst.write(text("©alb", it)) }
        tags.track?.let { n ->
            val value = ByteBuffer.allocate(8).putShort(0).putShort(n.toShort())
                .putShort((tags.trackCount ?: 0).toShort()).putShort(0).array()
            ilst.write(box("trkn", data(0, value)))
        }
        // iTunes metadata handler: 'mdir' / 'appl', the layout players expect.
        val hdlr = box("hdlr", ByteBuffer.allocate(25)
            .putInt(0).putInt(0).put("mdir".toByteArray()).put("appl".toByteArray())
            .putInt(0).putInt(0).put(0).array())
        val body = ByteArrayOutputStream()
        body.write(ByteArray(4))  // meta is a full box: version 0, flags 0
        body.write(hdlr)
        body.write(box("ilst", ilst.toByteArray()))
        return box("meta", body.toByteArray())
    }

    private fun text(type: String, value: String) = box(type, data(1, value.toByteArray(Charsets.UTF_8)))

    /** 'data' atom: type indicator (1 = UTF-8, 0 = implicit), locale 0, then the value. */
    private fun data(kind: Int, value: ByteArray) =
        box("data", ByteBuffer.allocate(8 + value.size).putInt(kind).putInt(0).put(value).array())

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size)
            .putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.ISO_8859_1))
            .put(payload)
            .array()
}
