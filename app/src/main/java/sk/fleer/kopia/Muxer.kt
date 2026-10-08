package sk.fleer.kopia

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Combines the separate video and audio files into one MP4 by copying samples
 * across - no decoding and no re-encoding, so it is near-instant and loses
 * nothing. This is why the app needs no ffmpeg: MediaMuxer is part of Android.
 *
 * Audio-only downloads skip this entirely - YouTube's itag 140 file is already a
 * valid M4A, so there is nothing to combine.
 */
object Muxer {

    private const val BUFFER = 1 shl 20

    fun combine(video: File, audio: File, dest: File) {
        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = mutableListOf<MediaExtractor>()
        try {
            val tracks = mutableListOf<Pair<MediaExtractor, Int>>()
            for (file in listOf(video, audio)) {
                val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
                sources += extractor
                val track = firstTrack(extractor)
                    ?: throw IOException("V súbore ${file.name} nie je použiteľná stopa.")
                extractor.selectTrack(track)
                tracks += extractor to muxer.addTrack(extractor.getTrackFormat(track))
            }

            muxer.start()
            val buffer = ByteBuffer.allocate(BUFFER)
            val info = MediaCodec.BufferInfo()
            for ((extractor, output) in tracks) {
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(output, buffer, info)
                    extractor.advance()
                }
            }
            muxer.stop()
        } finally {
            sources.forEach { runCatching { it.release() } }
            runCatching { muxer.release() }
        }
    }

    private fun firstTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/") || mime.startsWith("audio/")) return i
        }
        return null
    }
}
