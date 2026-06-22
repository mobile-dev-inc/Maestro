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

package maestro.android

import io.grpc.Status
import java.io.IOException

/**
 * Lifecycle of an [AndroidDeviceConnection].
 */
enum class ConnectionState {
    CONNECTING,
    CONNECTED,
    DEAD,
    RECONNECTING,
}

// ──────────────────────────────────────────────────────────────────────────────
// gRPC plane — the modelled outcome of a device-server call.
//
// Only transport *deaths* throw (see the Device*Exception types below). A call
// that the server actually answered — successfully or with an error status — is
// returned as a value so callers never have to reach for StatusRuntimeException.
// ──────────────────────────────────────────────────────────────────────────────

sealed interface DeviceResponse<out R> {

    data class Ok<out R>(val value: R) : DeviceResponse<R>

    data class Failure(
        /** Which call failed: "tap", "swipe", "deviceInfo". */
        val operation: String,
        /** Category reported by the server, e.g. INVALID_ARGUMENT. */
        val code: Status.Code,
        /** The server's own message. */
        val description: String,
        /** Structured detail pulled from the status trailers, if the server set any. */
        val details: List<ErrorDetail> = emptyList(),
    ) : DeviceResponse<Nothing>
}

/** A single structured error entry carried in a gRPC status' trailers. */
data class ErrorDetail(val key: String, val value: String)

/** Thrown by [orThrow] when the device server answered with a failure status. */
class DeviceCallFailedException(val failure: DeviceResponse.Failure) : IOException(
    buildString {
        append("'${failure.operation}' failed: ${failure.code}")
        if (failure.description.isNotBlank()) append(" - ${failure.description}")
        failure.details.forEach { append("\n  ${it.key}=${it.value}") }
    }
)

/** Unwrap a successful response or throw [DeviceCallFailedException] for a failure. */
fun <R> DeviceResponse<R>.orThrow(): R = when (this) {
    is DeviceResponse.Ok -> value
    is DeviceResponse.Failure -> throw DeviceCallFailedException(this)
}

// ──────────────────────────────────────────────────────────────────────────────
// Transport deaths — the exception TYPE is the failure mode; there is no `cause`
// enum. Shared diagnostics describe what was in flight when the pipe broke.
// ──────────────────────────────────────────────────────────────────────────────

data class DeviceDiagnostics(
    /** Which call was in flight when it died. */
    val operation: String,
    /** Original exception class + message. */
    val rootCause: String,
    val serial: String,
    /** Abrupt crash vs slow stall. */
    val msSinceLastByte: Long,
    val connectionAgeMs: Long,
)

/** MODE 1 — the device server died but adbd is still reachable. */
class DeviceServerDiedException(
    val diagnostics: DeviceDiagnostics,
    cause: Throwable,
) : IOException(
    "Device server died during '${diagnostics.operation}' on ${diagnostics.serial} " +
        "(${diagnostics.msSinceLastByte}ms since last byte, connection age ${diagnostics.connectionAgeMs}ms): " +
        diagnostics.rootCause,
    cause,
)

/** MODE 2 — adbd is gone; the whole device transport is unreachable. */
class DeviceUnreachableException(
    val diagnostics: DeviceDiagnostics,
    cause: Throwable,
) : IOException(
    "Device ${diagnostics.serial} is unreachable during '${diagnostics.operation}' " +
        "(${diagnostics.msSinceLastByte}ms since last byte, connection age ${diagnostics.connectionAgeMs}ms): " +
        diagnostics.rootCause,
    cause,
)

/** CONFIG — reconnecting won't help; a human must re-authorize the ADB key. */
class DeviceAuthException(
    val serial: String,
    cause: Throwable,
) : IOException("Device $serial is unauthorized; accept the ADB authorization prompt on the device", cause)

// ──────────────────────────────────────────────────────────────────────────────
// dadb plane — operation outcomes. dadb 2.0.0 RETURNS the outcome as a *Result; it
// only THROWS an AdbException for a transport death. A returned Failure carries
// dadb's own reason string. Transport deaths surface as the Device*Exception types
// above (all IOException). An operation failure is NOT a transport death, so
// orThrowOnFailure throws a RuntimeException, not an IOException — a catch(IOException)
// that exists to handle device death must never swallow an operation failure.
// ──────────────────────────────────────────────────────────────────────────────

sealed interface InstallResult {
    object Success : InstallResult
    data class Failure(val message: String, val cause: Throwable? = null) : InstallResult
}

sealed interface UninstallResult {
    object Success : UninstallResult
    data class Failure(val message: String, val cause: Throwable? = null) : UninstallResult
}

sealed interface SyncResult {
    object Success : SyncResult
    data class Failure(val message: String, val cause: Throwable? = null) : SyncResult
}

/**
 * Thrown by [orThrowOnFailure] when the device answered an operation with a Failure (install rejected,
 * sync FAILed, ...). Deliberately a [RuntimeException], NOT an [IOException], so a `catch (IOException)`
 * that exists to react to a transport death (the Device*Exception types) does not swallow it.
 */
class AndroidOperationFailedException(message: String) : RuntimeException(message)

fun InstallResult.orThrowOnFailure() {
    if (this is InstallResult.Failure) throw AndroidOperationFailedException(message)
}

fun UninstallResult.orThrowOnFailure() {
    if (this is UninstallResult.Failure) throw AndroidOperationFailedException(message)
}

fun SyncResult.orThrowOnFailure() {
    if (this is SyncResult.Failure) throw AndroidOperationFailedException(message)
}
