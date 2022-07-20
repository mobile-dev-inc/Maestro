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
