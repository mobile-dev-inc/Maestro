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

sealed class MaestroException(override val message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    class UnableToLaunchApp(message: String, cause: Throwable? = null) : MaestroException(message, cause)

    class AppCrash(message: String, cause: Throwable? = null): MaestroException(message, cause)

    class DriverTimeout(message: String, val debugMessage: String? = null, cause: Throwable? = null): MaestroException(message, cause)

    open class AssertionFailure(
        message: String,
        // Nullable so callers with no on-device view tree (e.g. the device-core path, which has no
        // serializable hierarchy) can omit it. TreeNode itself is removed later in the migration.
        val hierarchyRoot: TreeNode? = null,
        val debugMessage: String,
        cause: Throwable? = null,
    ) : MaestroException(message, cause)

    class ElementNotFound(
        message: String,
        hierarchyRoot: TreeNode? = null,
        debugMessage: String,
        cause: Throwable? = null,
    ) : AssertionFailure(message, hierarchyRoot, debugMessage, cause)

    class CloudApiKeyNotAvailable(message: String, cause: Throwable? = null) : MaestroException(message, cause)

    class DestinationIsNotWritable(message: String, cause: Throwable? = null) : MaestroException(message, cause)

    class UnableToCopyTextFromElement(message: String, cause: Throwable? = null): MaestroException(message, cause)

    class InvalidCommand(
        message: String,
        cause: Throwable? = null,
    ) : MaestroException(message, cause)

    class HideKeyboardFailure(message: String, cause: Throwable? = null, val debugMessage: String) : MaestroException(message, cause)

    class NoRootAccess(message: String, cause: Throwable? = null) : MaestroException(message, cause)

    class UnsupportedJavaVersion(message: String, cause: Throwable? = null) : MaestroException(message, cause)

    class MissingAppleTeamId(message: String, cause: Throwable? = null): MaestroException(message, cause)

    class IOSDeviceDriverSetupException(message: String, cause: Throwable? = null): MaestroException(message, cause)

    class NotImplemented(
        message: String,
        cause: Throwable? = null,
    ) : MaestroException(message, cause)
}

sealed class MaestroDriverStartupException(override val message: String, cause: Throwable? = null): RuntimeException(message, cause) {
    class AndroidDriverTimeoutException(message: String, cause: Throwable? = null): MaestroDriverStartupException(message, cause)
    class AndroidInstrumentationSetupFailure(message: String, cause: Throwable? = null): MaestroDriverStartupException(message, cause)
}
