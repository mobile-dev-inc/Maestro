package ios.grpc

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import idb.Idb
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver

typealias LogStreamListener = (Result<String, Throwable>) -> Boolean

class LogStream(private val listener: LogStreamListener) : ClientResponseObserver<Idb.LogRequest, Idb.LogResponse> {
    private var requestStream: ClientCallStreamObserver<Idb.LogRequest>? = null

    override fun onNext(value: Idb.LogResponse) {
        if (listener(Ok(value.output.toStringUtf8()))) {
            requestStream?.cancel("Cancelling the observing stream... received manual command from the task", null)
        }
    }

    override fun onError(t: Throwable?) {
        listener(Err(t ?: IllegalStateException()))
    }

    override fun onCompleted() {}

    override fun beforeStart(requestStream: ClientCallStreamObserver<Idb.LogRequest>?) {
        this.requestStream = requestStream
    }
}
