package maestro.utils

import io.grpc.stub.StreamObserver
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BlockingStreamObserver<T> : StreamObserver<T> {

    private val semaphore = Semaphore(0)

    private var result: T? = null
    private var error: Throwable? = null

    override fun onNext(value: T) {
        result = value
    }

    override fun onError(t: Throwable) {
        error = t
        semaphore.release()
    }

    override fun onCompleted() {
        semaphore.release()
    }

    fun awaitResult(): T {
        if (!semaphore.tryAcquire(10, TimeUnit.MINUTES)) {
            throw TimeoutException("Timeout waiting for Stream to pass Error or Completed message")
        }

        error?.let { throw it }

        return result ?: throw IllegalStateException("Result is missing")
    }
}