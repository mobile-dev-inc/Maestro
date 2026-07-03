/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.drivers

import org.jcodec.common.io.NIOUtils
import org.jcodec.containers.mp4.MP4TrackType
import org.jcodec.containers.mp4.demuxer.AbstractMP4DemuxerTrack
import org.jcodec.containers.mp4.demuxer.MP4Demuxer
import org.jcodec.containers.mp4.muxer.MP4Muxer
import org.jcodec.containers.mp4.muxer.MP4MuxerTrack
import java.io.File

/**
 * Stitches several H.264 `.mp4` files into one by copying the compressed samples (no re-encode).
 * Reassembles a screen recording captured in ≤180 s segments, since Android API < 34 `screenrecord`
 * hard-caps a single recording at 180 s.
 *
 * Two details keep the output valid for strict decoders/re-encoders (ffmpeg):
 *  - **Raw demux** ([MP4Demuxer.createRawMP4Demuxer]) copies samples exactly as stored — AVCC
 *    length-prefixed, no inlined parameter sets. The default demuxer rewrites samples to Annex-B and
 *    prepends SPS/PPS to every IDR, corrupting an `avc1` track at each keyframe.
 *  - **One constant frame rate** across all segments. `screenrecord` is variable-frame-rate; copying
 *    its timing through makes ffmpeg's re-encode collapse frames onto a guessed rate and drop
 *    colliding ones. A constant rate keeps PTS/DTS strictly increasing and every frame intact.
 */
object Mp4Concatenator {

    /**
     * Concatenates [inputs] (in order) into [output], overwriting it. Empty/missing/zero-length
     * inputs are skipped; a single input is a byte-for-byte pass-through.
     */
    fun concatenate(inputs: List<File>, output: File) {
        val segments = inputs.filter { it.isFile && it.length() > 0 }
        when {
            segments.isEmpty() -> return
            segments.size == 1 -> segments[0].copyTo(output, overwrite = true)
            else -> muxCopy(segments, output)
        }
    }

    private fun muxCopy(segments: List<File>, output: File) {
        val timeline = planTimeline(segments) ?: return

        val out = NIOUtils.writableChannel(output)
        try {
            val muxer = MP4Muxer.createMP4MuxerToChannel(out)
            var track: MP4MuxerTrack? = null
            var frameIndex = 0L

            for (segment in segments) {
                readVideoTrack(segment) { source ->
                    // Create the output track from the first segment that actually has one; all segments
                    // share one codec config, so its sample entries (SPS/PPS) describe them all.
                    val outTrack = track ?: muxer.addTrack(MP4MuxerTrack(muxer.nextTrackId, MP4TrackType.VIDEO)).also {
                        source.sampleEntries.forEach(it::addSampleEntry)
                        track = it
                    }
                    while (true) {
                        val packet = source.nextFrame() ?: break
                        // Verbatim sample, retimed onto the shared constant rate. Its KEY/INTER type is
                        // preserved, so each segment's opening IDR stays a sync sample.
                        packet.timescale = timeline.timescale
                        packet.pts = frameIndex * timeline.frameDuration
                        packet.duration = timeline.frameDuration
                        outTrack.addFrame(packet)
                        frameIndex++
                    }
                }
            }
            muxer.finish()
        } finally {
            NIOUtils.closeQuietly(out)
        }
    }

    /** Shared timescale and the single per-frame duration the whole stitched timeline runs at. */
    private class Timeline(val timescale: Int, val frameDuration: Long)

    /** Header-only pass over all segments to derive one constant frame duration. Null if no frames. */
    private fun planTimeline(segments: List<File>): Timeline? {
        var timescale = -1
        var totalFrames = 0L
        var totalTicks = 0L
        for (segment in segments) {
            readVideoTrack(segment) { track ->
                if (timescale == -1) timescale = track.timescale.toInt()
                totalFrames += track.frameCount
                totalTicks += Math.round(track.meta.totalDuration * timescale)
            }
        }
        if (totalFrames <= 0L || timescale <= 0) return null
        // At least 1 tick so PTS/DTS strictly increase even for tiny inputs.
        return Timeline(timescale, maxOf(1L, Math.round(totalTicks.toDouble() / totalFrames)))
    }

    /** Opens [segment]'s raw video track, passes it to [block], and always closes the channel. */
    private inline fun readVideoTrack(segment: File, block: (AbstractMP4DemuxerTrack) -> Unit) {
        val channel = NIOUtils.readableChannel(segment)
        try {
            (MP4Demuxer.createRawMP4Demuxer(channel).videoTrack as? AbstractMP4DemuxerTrack)?.let(block)
        } finally {
            NIOUtils.closeQuietly(channel)
        }
    }
}
