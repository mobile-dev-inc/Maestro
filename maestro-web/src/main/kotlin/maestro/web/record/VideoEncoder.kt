package maestro.web.record

import okio.Sink

/**
 * Encodes frames that arrive at irregular wall-clock times into a video whose timeline is
 * wall-clock: each frame is shown from [encodeFrame]'s `atMs` until the next frame, and the
 * last one until [finish]'s `endMs`. Times are milliseconds since the recording started, so
 * video 0:00 is the recording's start, whenever the first frame happened to arrive.
 */
interface VideoEncoder {

    fun start(out: Sink)

    fun encodeFrame(frame: ByteArray, atMs: Long)

    fun finish(endMs: Long)

}
