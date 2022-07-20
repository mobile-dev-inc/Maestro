import maestro.Maestro
import maestro.orchestra.AssertCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.TapOnElementCommand
import dadb.Dadb
import io.grpc.ManagedChannelBuilder
import ios.IOSDevice
import ios.idb.IdbIOSDevice
import java.io.File

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

fun main() {
    executeAndroidCommands()
    executeIOSCommands()
}

private fun executeAndroidCommands() {
    val dadb = Dadb.create("localhost", 5555)
    val androidApk = File("./examples/samples/src/main/resources/android-app-debug.apk")
    dadb.install(androidApk)
    val maestro = Maestro.android(dadb)
    val launchAppCommand = MaestroCommand(launchAppCommand = LaunchAppCommand("dev.mobile.sample"))
    val tapViewDetailsCommand = MaestroCommand(
        tapOnElement = TapOnElementCommand(ElementSelector(textRegex = "VIEW DETAILS"))
    )
    val assertCommand = MaestroCommand(
        assertCommand = AssertCommand(ElementSelector(textRegex = "Here is the detailed content"))
    )
    maestro.use {
        Orchestra(it).executeCommands(
            listOf(
                launchAppCommand,
                tapViewDetailsCommand,
                assertCommand
            )
        )
    }
}

private fun executeIOSCommands() {
    val localhost = "localhost"
    val port = 10883
    val iosArchive = File("./examples/samples/src/main/resources/CovidCertificateVerifier.zip").inputStream()
    val channel = ManagedChannelBuilder.forAddress(localhost, port)
        .usePlaintext()
        .build()
    IdbIOSDevice(channel).use { it.install(iosArchive) }
    val maestro = Maestro.ios(localhost, port)
    val launchAppCommand = MaestroCommand(
        launchAppCommand = LaunchAppCommand("ch.admin.bag.covidcertificate.verifier.dev")
    )
    val tapOnElementCommand = MaestroCommand(
        tapOnElement = TapOnElementCommand(
            ElementSelector(textRegex = "HOW IT WORKS")
        )
    )
    val assertCommand = MaestroCommand(
        assertCommand = AssertCommand(
            ElementSelector(textRegex = "HOW IT WORKS")
        )
    )
    maestro.use {
        Orchestra(maestro).executeCommands(
            listOf(launchAppCommand, tapOnElementCommand, assertCommand)
        )
    }
}