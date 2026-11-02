package maestro.android

import com.google.common.truth.Truth.assertThat
import dadb.AdbStream
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Test
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeadlineDadbTest {

    @Test
    fun `an APK pull whose adb stream stops answering is cut at the deadline and retried`() {
        val stalled = StalledSocketStream()
        val syncStreams = ConcurrentLinkedQueue<AdbStream>(listOf(stalled, ReplyStream(syncData("apk-bytes"))))
        val dadb = DeadlineDadb(
            FakeDadb(onOpen = { destination ->
                when {
                    destination.startsWith("shell,v2,raw:pm list packages") ->
                        ReplyStream(shellStdout("package:/data/app/base.apk=com.example.app\n"))
                    destination == "sync:" -> syncStreams.poll()
                    else -> error("unexpected adb open: $destination")
                }
            }),
            deadlineMs = 200,
        )

        try {
            val apk = AndroidAppFiles.getApkFile(AndroidDeviceConnection.forTest(dadb = dadb), "com.example.app")

            assertThat(apk.readText()).isEqualTo("apk-bytes")
            // Only a close releases the stalled read, so this proves the deadline closed the stuck stream.
            assertThat(stalled.closed.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            stalled.close()
        }
    }

    /** Parks reads until closed and ignores interrupts, like a blocking socket read on JDK 17. */
    private class StalledSocketStream : AdbStream {
        private val parked = InterruptProofLatch()
        val closed = CountDownLatch(1)

        override val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                parked.awaitUninterruptibly()
                throw SocketException("Socket closed")
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }.buffer()

        override val sink = Buffer()

        override fun close() {
            closed.countDown()
            parked.release()
        }
    }

    /** An adb stream that answers with [reply] and accepts any request. */
    private class ReplyStream(reply: Buffer) : AdbStream {
        override val source = reply
        override val sink = Buffer()
        override fun close() = Unit
    }

    /** A shell v2 reply: one stdout packet, then exit code 0. */
    private fun shellStdout(text: String): Buffer {
        val bytes = text.toByteArray()
        return Buffer()
            .writeByte(1).writeIntLe(bytes.size).write(bytes)
            .writeByte(3).writeIntLe(1).writeByte(0)
    }

    /** A sync RECV reply: one DATA packet, then DONE. */
    private fun syncData(text: String): Buffer {
        val bytes = text.toByteArray()
        return Buffer()
            .writeUtf8("DATA").writeIntLe(bytes.size).write(bytes)
            .writeUtf8("DONE").writeIntLe(0)
    }
}
