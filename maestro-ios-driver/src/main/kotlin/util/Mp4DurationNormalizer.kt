package util

import org.jcodec.containers.mp4.boxes.MediaHeaderBox
import org.jcodec.containers.mp4.boxes.MovieBox
import org.jcodec.containers.mp4.boxes.MovieFragmentBox
import org.jcodec.containers.mp4.boxes.TimeToSampleBox.TimeToSampleEntry
import org.jcodec.movtool.MP4Edit
import org.jcodec.movtool.RelocateMP4Editor
import org.slf4j.LoggerFactory
import java.io.File

/**
 * simctl records variable-frame-rate video: a static final screen stops emitting
 * frames, so the sample timeline (stts/mdhd) ends before the movie-header length,
 * and Chrome's <video> reports that shorter duration. Extends the final sample so
 * the media duration matches the movie duration. Lossless (only moov metadata
 * changes); best-effort (leaves the file as-is on failure).
 */
object Mp4DurationNormalizer {

    private val logger = LoggerFactory.getLogger(Mp4DurationNormalizer::class.java)

    fun normalize(recording: File) {
        try {
            RelocateMP4Editor().modifyOrRelocate(recording, HoldLastFrameToMovieDuration)
        } catch (e: Exception) {
            logger.warn("Skipping recording duration normalization for ${recording.name}: ${e.message}")
        }
    }

    private object HoldLastFrameToMovieDuration : MP4Edit {
        override fun apply(mov: MovieBox) {
            val video = mov.videoTrack ?: return
            val stts = video.stts ?: return
            val entries = stts.entries
            if (entries.isEmpty()) return

            val movieTimescale = mov.timescale.toLong()
            val mediaTimescale = video.timescale.toLong()
            if (movieTimescale <= 0L || mediaTimescale <= 0L) return

            // movie length in the media timescale
            val target = mov.duration * mediaTimescale / movieTimescale
            val current = entries.sumOf { it.sampleCount.toLong() * it.sampleDuration }
            val gap = target - current
            if (gap <= 0L) return // already consistent

            val last = entries.last()
            val held = last.sampleDuration + gap
            if (held > Int.MAX_VALUE) return
            val heldDuration = held.toInt()

            // hold only the final frame; leave every other sample's cadence intact
            stts.entries = if (last.sampleCount <= 1) {
                entries.copyOf().also { it[it.lastIndex] = TimeToSampleEntry(1, heldDuration) }
            } else {
                (entries.dropLast(1) +
                    TimeToSampleEntry(last.sampleCount - 1, last.sampleDuration) +
                    TimeToSampleEntry(1, heldDuration)).toTypedArray()
            }

            // keep mdhd in sync with the sample table
            video.mdia?.boxes?.filterIsInstance<MediaHeaderBox>()?.firstOrNull()
                ?.duration = target
        }

        override fun applyToFragment(mov: MovieBox, fragments: Array<out MovieFragmentBox>) = Unit
    }
}
