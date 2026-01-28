package dev.mobile.maestro

import io.grpc.Status
import io.grpc.StatusException

/**
 * Converts a Throwable to a gRPC INTERNAL StatusException.
 */
internal fun Throwable.internalError(): StatusException {
    val description = message ?: javaClass.simpleName
    return Status.INTERNAL
        .withDescription(description)
        .withCause(this)
        .asException()
}
