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

package maestro

sealed class MaestroException(override val message: String) : RuntimeException(message) {

    class UnableToLaunchApp(message: String) : MaestroException(message)

    class UnableToClearState(message: String) : MaestroException(message)

    class AppCrash(message: String): MaestroException(message)

    class DriverTimeout(message: String): MaestroException(message)

    open class AssertionFailure(
        message: String,
        val hierarchyRoot: TreeNode,
    ) : MaestroException(message)

    class ElementNotFound(
        message: String,
        hierarchyRoot: TreeNode,
    ) : AssertionFailure(message, hierarchyRoot)

    class CloudApiKeyNotAvailable(message: String) : MaestroException(message)

    class DestinationIsNotWritable(message: String) : MaestroException(message)

    class UnableToCopyTextFromElement(message: String): MaestroException(message)

    class InvalidCommand(
        message: String,
    ) : MaestroException(message)

    class NoRootAccess(message: String) : MaestroException(message)

    class UnsupportedJavaVersion(message: String) : MaestroException(message)

    class MissingAppleTeamId(message: String): MaestroException(message)

    class IOSDeviceDriverSetupException(message: String): MaestroException(message)
}

sealed class MaestroDriverStartupException(override val message: String): RuntimeException() {
    class AndroidDriverTimeoutException(message: String): MaestroDriverStartupException(message)
    class AndroidInstrumentationSetupFailure(message: String): MaestroDriverStartupException(message)
}
