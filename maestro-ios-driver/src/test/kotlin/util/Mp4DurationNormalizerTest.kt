package util

import com.google.common.truth.Truth.assertThat
import org.jcodec.containers.mp4.MP4Util
import org.jcodec.containers.mp4.boxes.MediaHeaderBox
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class Mp4DurationNormalizerTest {

    /** Video-track sample-timeline duration (sum of stts entries), in media units. */
    private fun mediaDuration(file: File): Long {
        val video = MP4Util.parseMovie(file).videoTrack
        return video.stts.entries.sumOf { it.sampleCount.toLong() * it.sampleDuration }
    }

    /** Movie-header duration rescaled into the video track's media timescale. */
    private fun movieDurationInMediaUnits(file: File): Long {
        val mov = MP4Util.parseMovie(file)
        return mov.duration * mov.videoTrack.timescale.toLong() / mov.timescale.toLong()
    }

    private fun fixtureCopy(): File {
        val fixture = File(javaClass.getResource("/mp4/short_media.mov")!!.toURI())
        val tmp = Files.createTempFile("recording", ".mov").toFile()
        fixture.copyTo(tmp, overwrite = true)
        return tmp
    }

    @Test
    fun `extends the final sample so media duration matches the movie duration`() {
        val file = fixtureCopy()
        val target = movieDurationInMediaUnits(file)
        val before = mediaDuration(file)
        // Precondition: the fixture is genuinely mismatched (media shorter than movie).
        assertThat(before).isLessThan(target)
        assertThat(before).isNotEqualTo(target)

        Mp4DurationNormalizer.normalize(file)

        val after = mediaDuration(file)
        assertThat(after).isEqualTo(target) // media now matches the movie duration
        assertThat(after).isNotEqualTo(before) // and it actually changed
        val mdhd = MP4Util.parseMovie(file).videoTrack.mdia.boxes
            .filterIsInstance<MediaHeaderBox>().first()
        assertThat(mdhd.duration).isEqualTo(target)
        // The movie header itself is left untouched.
        assertThat(MP4Util.parseMovie(file).duration).isEqualTo(6000L)
        file.delete()
    }

    @Test
    fun `is a no-op once the recording is already consistent`() {
        val file = fixtureCopy()
        Mp4DurationNormalizer.normalize(file)
        val afterFirst = mediaDuration(file)

        Mp4DurationNormalizer.normalize(file)

        assertThat(mediaDuration(file)).isEqualTo(afterFirst)
        file.delete()
    }

    @Test
    fun `leaves a non-video file untouched instead of throwing`() {
        val notVideo = Files.createTempFile("junk", ".mov").toFile().apply { writeText("not an mp4") }
        Mp4DurationNormalizer.normalize(notVideo) // must not throw
        assertThat(notVideo.readText()).isEqualTo("not an mp4")
        notVideo.delete()
    }
}
