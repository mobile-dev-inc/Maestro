import dadb.Dadb
import maestro.Maestro
import maestro.drivers.AndroidDriver
import maestro.orchestra.AssertCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.TapOnElementCommand
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
}

private fun executeAndroidCommands() {
    val dadb = Dadb.create("localhost", 5555)
    val androidApk = File("./e2e/apps/demo_app.apk")
    dadb.install(androidApk)
    val driver = AndroidDriver(dadb)
    val maestro = Maestro.android(driver)

    val commands = listOf(
        MaestroCommand(
            launchAppCommand = LaunchAppCommand("com.example.example"),
        ),
        MaestroCommand(
            tapOnElement = TapOnElementCommand(ElementSelector(idRegex = "fabAddIcon")),
        ),
        MaestroCommand(
            tapOnElement = TapOnElementCommand(ElementSelector(idRegex = "fabAddIcon")),
        ),
        MaestroCommand(
            assertCommand = AssertCommand(
                ElementSelector(textRegex = "2"),
            ),
        ),
    )

    maestro.use {
        Orchestra(it).executeCommands(commands)
    }
}
