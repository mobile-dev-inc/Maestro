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

import com.google.common.truth.Truth.assertThat
import dadb.AdbShellResponse
import dadb.AdbStream
import dadb.Dadb
import dadb.InstallResult as DadbInstallResult
import dadb.SyncResult as DadbSyncResult
import dadb.UninstallResult as DadbUninstallResult
import io.grpc.Status
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.android.AndroidDeviceConnection
import maestro.android.DeviceCallFailedException
import maestro_android.MaestroAndroid.EmptyResponse
import maestro_android.MaestroDriverGrpc
import okio.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Unit tests for [AndroidDriver.releaseSlot] / [AndroidDriver.reacquireSlot] (Task 4).
 *
 * These are the host-side halves of the Orchestra-level UiAutomation slot lease. We don't mock
 * [AndroidDeviceConnection] or its `execute` directly — mocking a generic method that returns the
 * sealed `DeviceResponse<R>` trips MockK's return-value instantiation (see
 * `AndroidDeviceConnectionTest`, which sidesteps the same trap). Instead we follow that file's
 * established pattern: a real [AndroidDeviceConnection] built via [AndroidDeviceConnection.forTest]
 * over a fake [Dadb], with only the gRPC blocking stub mocked. `execute` then runs for real, so a
 * passing test proves both that the driver calls the right RPC AND that a server-answered failure
 * carries the right operation label through `DeviceCallFailedException`.
 */
class AndroidDriverSlotTest {

    // Minimal fake dadb: slot lease tests never touch the dadb plane, so every method errors if called.
    private class FakeDadb : Dadb {
        override fun open(destination: String): AdbStream = error("open not stubbed")
        override fun supportsFeature(feature: String): Boolean = true
        override fun shell(command: String): AdbShellResponse = error("shell not stubbed")
        override fun install(file: File, vararg options: String): DadbInstallResult = error("install not stubbed")
        override fun uninstall(packageName: String): DadbUninstallResult = error("uninstall not stubbed")
        override fun pull(dst: File, remotePath: String): DadbSyncResult = error("pull(File) not stubbed")
        override fun pull(sink: Sink, remotePath: String): DadbSyncResult = error("pull(Sink) not stubbed")
        override fun push(src: File, remotePath: String, mode: Int, lastModifiedMs: Long): DadbSyncResult =
            error("push not stubbed")
        override fun close() {}
        override fun toString() = "fake-serial"
    }

    private fun driver(blockingStub: MaestroDriverGrpc.MaestroDriverBlockingStub): AndroidDriver {
        val connection = AndroidDeviceConnection.forTest(
            dadb = FakeDadb(),
            blockingStubProvider = { blockingStub },
        )
        return AndroidDriver(connection = connection)
    }

    @Test
    fun `releaseSlot invokes the releaseSlot rpc`() {
        val blockingStub = mockk<MaestroDriverGrpc.MaestroDriverBlockingStub>(relaxed = true)
        every { blockingStub.releaseSlot(any()) } returns EmptyResponse.getDefaultInstance()

        driver(blockingStub).releaseSlot()

        verify(exactly = 1) { blockingStub.releaseSlot(any()) }
        verify(exactly = 0) { blockingStub.reacquireSlot(any()) }
    }

    @Test
    fun `reacquireSlot invokes the reacquireSlot rpc`() {
        val blockingStub = mockk<MaestroDriverGrpc.MaestroDriverBlockingStub>(relaxed = true)
        every { blockingStub.reacquireSlot(any()) } returns EmptyResponse.getDefaultInstance()

        driver(blockingStub).reacquireSlot()

        verify(exactly = 1) { blockingStub.reacquireSlot(any()) }
        verify(exactly = 0) { blockingStub.releaseSlot(any()) }
    }

    @Test
    fun `reacquireSlot failure is swallowed and logged so it cannot mask the assert verdict`() {
        // reacquireSlot runs in DeviceCoreAssertRouter.evaluate()'s bare `finally`. If it threw, JVM
        // try/finally semantics would discard an already-computed inspect verdict (Task 6's critical
        // masking mode). So an unrecoverable device-side failure must be swallowed, not rethrown.
        val blockingStub = mockk<MaestroDriverGrpc.MaestroDriverBlockingStub>(relaxed = true)
        every { blockingStub.reacquireSlot(any()) } throws
            Status.INTERNAL.withDescription("slot never freed").asRuntimeException()

        // Must NOT throw (unlike releaseSlot, which is outside the try and DOES surface).
        driver(blockingStub).reacquireSlot()

        verify(exactly = 1) { blockingStub.reacquireSlot(any()) }
    }

    @Test
    fun `releaseSlot failure surfaces as DeviceCallFailedException carrying the releaseSlot operation label`() {
        val blockingStub = mockk<MaestroDriverGrpc.MaestroDriverBlockingStub>(relaxed = true)
        every { blockingStub.releaseSlot(any()) } throws
            Status.INTERNAL.withDescription("UiAutomation.destroy() reflection failed").asRuntimeException()

        val ex = assertThrows<DeviceCallFailedException> { driver(blockingStub).releaseSlot() }

        assertThat(ex.failure.operation).isEqualTo("releaseSlot")
    }
}
