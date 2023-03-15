package ios.idb

import io.grpc.ManagedChannel

interface IdbRunner {
    fun stop(channel: ManagedChannel)

    fun start(): ManagedChannel
}
