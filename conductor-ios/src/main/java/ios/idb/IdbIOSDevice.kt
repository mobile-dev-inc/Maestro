package ios.idb

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching
import com.google.gson.Gson
import com.google.protobuf.ByteString
import idb.CompanionServiceGrpc
import idb.HIDEventKt
import idb.Idb
import idb.accessibilityInfoRequest
import idb.hIDEvent
import idb.installRequest
import idb.launchRequest
import idb.payload
import idb.point
import idb.targetDescriptionRequest
import idb.terminateRequest
import idb.uninstallRequest
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import ios.IOSDevice
import ios.device.AccessibilityNode
import ios.device.DeviceInfo
import ios.grpc.BlockingStreamObserver
import java.io.InputStream

class IdbIOSDevice(
    private val channel: ManagedChannel,
) : IOSDevice {

    private val blockingStub = CompanionServiceGrpc.newBlockingStub(channel)
    private val asyncStub = CompanionServiceGrpc.newStub(channel)

    override fun deviceInfo(): Result<DeviceInfo, Throwable> {
        return runCatching {
            val response = blockingStub.describe(targetDescriptionRequest {})
            val screenDimensions = response.targetDescription.screenDimensions

            DeviceInfo(
                widthPixels = screenDimensions.width.toInt(),
                heightPixels = screenDimensions.height.toInt()
            )
        }
    }

    override fun contentDescriptor(): Result<List<AccessibilityNode>, Throwable> {
        return runCatching {
            val response = blockingStub.accessibilityInfo(accessibilityInfoRequest {})

            GSON.fromJson(response.json, Array<AccessibilityNode>::class.java)
                .toList()
        }
    }

    override fun tap(x: Int, y: Int): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.HIDResponse>()
            val stream = asyncStub.hid(responseObserver)

            val pressAction = HIDEventKt.hIDPressAction {
                touch = HIDEventKt.hIDTouch {
                    point = point {
                        this.x = x.toDouble()
                        this.y = y.toDouble()
                    }
                }
            }

            stream.onNext(
                hIDEvent {
                    press = HIDEventKt.hIDPress {
                        action = pressAction
                        direction = Idb.HIDEvent.HIDDirection.DOWN
                    }
                }
            )
            Thread.sleep(50)
            stream.onNext(
                hIDEvent {
                    press = HIDEventKt.hIDPress {
                        action = pressAction
                        direction = Idb.HIDEvent.HIDDirection.UP
                    }
                }
            )
            stream.onCompleted()
        }
    }

    override fun scroll(xStart: Int, yStart: Int, xEnd: Int, yEnd: Int): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.HIDResponse>()
            val stream = asyncStub.hid(responseObserver)

            val swipeAction = HIDEventKt.hIDSwipe {
                this.start = point {
                    this.x = xStart.toDouble()
                    this.y = yStart.toDouble()
                }
                this.end = point {
                    this.x = xEnd.toDouble()
                    this.y = yEnd.toDouble()
                }
            }

            stream.onNext(
                hIDEvent {
                    swipe = swipeAction
                }
            )
            stream.onCompleted()
        }
    }

    override fun input(text: String): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.HIDResponse>()
            val stream = asyncStub.hid(responseObserver)

            TextInputUtil.textToListOfEvents(text)
                .forEach {
                    stream.onNext(it)
                }
            stream.onCompleted()
        }
    }

    override fun install(stream: InputStream): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.InstallResponse>()
            val requestStream = asyncStub.install(responseObserver)

            requestStream.onNext(
                installRequest {
                    destination = Idb.InstallRequest.Destination.APP
                    payload = payload {
                        compression = Idb.Payload.Compression.GZIP
                    }
                }
            )

            stream.buffered()
                .iterator()
                .asSequence()
                .chunked(CHUNK_SIZE)
                .forEach {
                    requestStream.onNext(
                        installRequest {
                            payload = payload {
                                data = ByteString.copyFrom(it.toByteArray())
                            }
                        }
                    )
                }
            requestStream.onCompleted()

            responseObserver.awaitResult()
        }
    }

    override fun uninstall(id: String): Result<Unit, Throwable> {
        return runCatching {
            try {
                blockingStub.uninstall(
                    uninstallRequest {
                        bundleId = id
                    }
                )
            } catch (e: StatusRuntimeException) {
                if (e.status.description != "$id is not installed") {
                    throw e
                }
            }
        }
    }

    override fun launch(id: String, isWarmup: Boolean): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.LaunchResponse>()
            val stream = asyncStub.launch(responseObserver)
            stream.onNext(
                launchRequest {
                    start = idb.LaunchRequestKt.start {
                        bundleId = id
                        foregroundIfRunning = isWarmup
                    }
                }
            )

            stream.onCompleted()
            responseObserver.awaitResult()
        }
    }

    override fun stop(id: String): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.TerminateResponse>()
            asyncStub.terminate(
                terminateRequest {
                    bundleId = id
                },
                responseObserver
            )

            responseObserver.awaitResult()
        }
    }

    override fun close() {
        channel.shutdown()
    }

    companion object {
        // 4Mb, the default max read for gRPC
        private const val CHUNK_SIZE = 1024 * 1024 * 3
        private val GSON = Gson()
    }
}
