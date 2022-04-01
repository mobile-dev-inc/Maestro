package conductor.android

import dadb.Dadb
import java.io.InterruptedIOException

class InstrumentationThread(private val dadb: Dadb) : Thread() {

    @Suppress("TooGenericExceptionCaught")
    override fun run() {
        super.run()
        try {
            runConductor(dadb)
        } catch (ignored: InterruptedException) {
            // Do nothing
        } catch (ignored: InterruptedIOException) {
            // Do nothing
        }
    }

    private fun runConductor(dadb: Dadb) {
        dadb.shell(
            "am instrument -w -m -e debug false " +
                "-e class 'dev.mobile.conductor.ConductorDriverService#grpcServer' " +
                "dev.mobile.conductor.test/androidx.test.runner.AndroidJUnitRunner"
        )
    }
}
