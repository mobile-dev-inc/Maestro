package maestro.drivers

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Proves [Mp4Concatenator] copies compressed H.264 samples correctly: captures real screenrecord
 * segments off the emulator, concatenates them, and validates the result with **ffmpeg** — a strict,
 * independent decoder. (jcodec, which wrote the file, tolerates the mangled NAL framing / dropped
 * keyframe flags that corrupt each boundary.) Skipped when the emulator or ffmpeg is absent.
 */
internal class Mp4ConcatenatorTest {

    private val device = AdbTestDevice()

    @Test
    internal fun `concatenated video decodes cleanly with no dropped frames or lost keyframes`(@TempDir tempDir: Path) {
        assumeTrue(device.isReachable(), "No emulator reachable via adb; skipping")
        assumeTrue(Ffmpeg.available(), "ffmpeg/ffprobe not found; skipping")

        // Given three real ~4s screenrecord segments
        val segments = (0 until 3).map { device.captureSegment(it, seconds = 4, tempDir) }
        val segmentFrames = segments.map { Ffmpeg.decodedFrameCount(it) }
        val expectedFrames = segmentFrames.sum()
        val expectedDuration = segments.sumOf { Ffmpeg.durationSeconds(it) }

        // When concatenated
        val output = tempDir.resolve("concatenated.mp4").toFile()
        Mp4Concatenator.concatenate(segments, output)
        assertThat(output.length()).isGreaterThan(0L)

        // Then every boundary is correctly framed — a strict decode reports no errors...
        assertThat(Ffmpeg.decodeErrors(output)).isEmpty()

        // ...no frame is dropped on a re-encode round-trip...
        val reencodedFrames = Ffmpeg.reencodedFrameCount(output, tempDir)
        println("Concat: per-segment frames=$segmentFrames (sum=$expectedFrames), re-encoded=$reencodedFrames")
        assertThat(reencodedFrames).isEqualTo(expectedFrames)

        // ...each source segment's opening frame stays a keyframe...
        val flags = Ffmpeg.packetFlags(output)
        assertThat(flags.size.toLong()).isEqualTo(expectedFrames)
        var boundary = 0
        for (frames in segmentFrames) {
            assertThat(flags[boundary]).contains("K")
            boundary += frames.toInt()
        }

        // ...and the total duration is the sum of the segments.
        assertThat(Ffmpeg.durationSeconds(output)).isWithin(0.20).of(expectedDuration)
    }

    @Test
    internal fun `single segment is a valid pass-through`(@TempDir tempDir: Path) {
        assumeTrue(device.isReachable(), "No emulator reachable via adb; skipping")
        assumeTrue(Ffmpeg.available(), "ffmpeg/ffprobe not found; skipping")

        val segment = device.captureSegment(0, seconds = 4, tempDir)
        val output = tempDir.resolve("single.mp4").toFile()
        Mp4Concatenator.concatenate(listOf(segment), output)

        // A single input is copied byte-for-byte, so it stays a valid mp4 with the source's exact
        // frames and — unlike the multi-segment path — its original device timing.
        assertThat(output.readBytes()).isEqualTo(segment.readBytes())
        assertThat(Ffmpeg.decodeErrors(output)).isEmpty()
        assertThat(Ffmpeg.decodedFrameCount(output)).isEqualTo(Ffmpeg.decodedFrameCount(segment))
        assertThat(Ffmpeg.durationSeconds(output)).isWithin(0.05).of(Ffmpeg.durationSeconds(segment))
    }

    @Test
    internal fun `empty input list is a no-op`(@TempDir tempDir: Path) {
        val output = tempDir.resolve("none.mp4").toFile()
        Mp4Concatenator.concatenate(emptyList(), output)
        assertThat(output.exists()).isFalse()
    }
}
