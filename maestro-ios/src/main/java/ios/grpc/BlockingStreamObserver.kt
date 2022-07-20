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
        if (!semaphore.tryAcquire(120, TimeUnit.SECONDS)) {
            throw TimeoutException()
        }

        error?.let { throw it }

        return result ?: throw IllegalStateException("Result is missing")
    }
}
