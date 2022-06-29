package conductor.cli.util

import conductor.Conductor
import dadb.Dadb

object ConductorFactory {

    fun createConductor(os: String): Conductor {
        return when (os) {
            "android" -> {
                val dadb = Dadb.discover("localhost")
                if (dadb == null) {
                    println("No Android devices found")
                    throw IllegalStateException()
                }

                Conductor.android(dadb)
            }
            "ios" -> {
                Conductor.ios("localhost", 10883)
            }
            else -> throw IllegalStateException("Unknown os: $os")
        }
    }
}
