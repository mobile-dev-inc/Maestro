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
