package sk.fleer.kopia

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Tags every .m4a/.mp4 in $KOPIA_MP4_SAMPLES into a ".tagged" copy, for checking
 * with ffprobe/ffmpeg that the tags are there and the media decodes unchanged.
 */
class Mp4TagsSampleTest {
    @Test
    fun tagsEverySample() {
        val dir = System.getenv("KOPIA_MP4_SAMPLES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val samples = dir!!.listFiles { f -> f.name.matches(Regex(""".*\.(m4a|mp4)""")) && !f.name.contains(".tagged") }!!
        for (sample in samples.sortedBy { it.name }) {
            val copy = File(dir, sample.nameWithoutExtension + ".tagged." + sample.extension)
            sample.copyTo(copy, overwrite = true)
            val ok = Mp4Tags.write(copy, Mp4Tags.Tags(
                title = "Skúška: ${sample.nameWithoutExtension}",
                artist = "ELÁN",
                album = "Detektívka",
                track = 7,
                trackCount = 11,
            ))
            println("${sample.name}: tagged=$ok ${sample.length()} -> ${copy.length()} B")
            assertTrue("${sample.name} was not tagged", ok)
        }
    }
}
