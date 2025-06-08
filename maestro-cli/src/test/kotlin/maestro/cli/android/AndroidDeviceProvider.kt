package maestro.cli.android

import dadb.Dadb
import dadb.adbserver.AdbServer

class AndroidDeviceProvider {

    fun local(): Dadb {
        val dadb = AdbServer.createDadb(connectTimeout = 60_000, socketTimeout = 60_000)

        return dadb
    }
}