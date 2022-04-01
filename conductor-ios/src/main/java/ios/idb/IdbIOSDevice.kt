package ios.idb

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching
import com.google.gson.Gson
import com.google.protobuf.ByteString
import idb.CompanionServiceGrpc
import idb.HIDEventKt
import idb.Idb
import idb.PushRequestKt.inner
import idb.accessibilityInfoRequest
import idb.fileContainer
import idb.hIDEvent
import idb.installRequest
import idb.instrumentsRunRequest
import idb.launchRequest
import idb.logRequest
import idb.payload
import idb.point
import idb.pushRequest
import idb.rmRequest
import idb.targetDescriptionRequest
import idb.terminateRequest
import idb.uninstallRequest
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import ios.IOSDevice
import ios.device.AccessibilityNode
import ios.device.DeviceInfo
import ios.grpc.BlockingStreamObserver
import ios.grpc.LogStream
import ios.grpc.LogStreamListener
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

class IdbIOSDevice(
    private val channel: ManagedChannel,
    override val host: String,
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

    override fun pushAppFiles(appBundleId: String, stream: InputStream): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.PushResponse>()
            val requestStream = asyncStub.push(responseObserver)

            requestStream.onNext(
                pushRequest {
                    inner = inner {
                        dstPath = appBundleId
                        container = fileContainer {
                            kind = Idb.FileContainer.Kind.APPLICATION_CONTAINER
                        }
                    }
                }
            )

            stream.buffered()
                .iterator()
                .asSequence()
                .chunked(CHUNK_SIZE)
                .forEach {
                    requestStream.onNext(
                        pushRequest {
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

    override fun log(deadlineDuration: Duration, listener: LogStreamListener) {
        val responseObserver = LogStream(listener)

        asyncStub.withDeadlineAfter(deadlineDuration.toMillis(), TimeUnit.MILLISECONDS).log(
            logRequest {
                source = Idb.LogRequest.Source.TARGET
            },
            responseObserver
        )
    }

    override fun clearChannel() {
        channel.shutdown()
    }

    override fun instruments(
        id: String,
        template: InstrumentsTemplate,
        operationDurationSeconds: Double,
        stopDelaySeconds: Double,
        listener: (Result<Path, Throwable>) -> Unit,
    ) {
        try {
            val tmpFilePath = Files.createTempFile("trace_tmp", "tar.gz")
            val fileStream = FileOutputStream(tmpFilePath.absolutePathString())

            val responseObserver = object : StreamObserver<Idb.InstrumentsRunResponse> {
                override fun onNext(value: Idb.InstrumentsRunResponse) {
                    if (!value.payload.data.isEmpty) {
                        val byteChunk = value.payload.data.toByteArray()
                        fileStream.write(byteChunk)
                    }
                }

                override fun onError(t: Throwable?) {
                    fileStream.close()
                    listener(Err(t ?: IllegalStateException()))
                }

                override fun onCompleted() {
                    fileStream.close()

                    listener(Ok(tmpFilePath))
                }
            }

            val requestStream = asyncStub.instrumentsRun(responseObserver)

            LOGGER.info("[START] Xcode Instruments with template ${template.templateName}, appBundleId = $id")
            requestStream.onNext(
                instrumentsRunRequest {
                    start = idb.InstrumentsRunRequestKt.start {
                        templateName = template.templateName
                        appBundleId = id
                        timings = idb.InstrumentsRunRequestKt.instrumentsTimings {
                            operationDuration = operationDurationSeconds
                        }
                    }
                }
            )

            Thread.sleep((stopDelaySeconds * 1000).toLong())

            LOGGER.info("[STOP] Xcode Instruments with template ${template.templateName}, appBundleId = $id")
            requestStream.onNext(
                instrumentsRunRequest {
                    stop = idb.InstrumentsRunRequestKt.stop {}
                }
            )

            requestStream.onCompleted()
        } catch (e: IOException) {
            listener(Err(e))
        }
    }

    override fun clearAppData(appBundleId: String): Result<Unit, Throwable> {
        return runCatching {
            blockingStub.rm(
                rmRequest {
                    container = fileContainer {
                        kind = Idb.FileContainer.Kind.APPLICATION_CONTAINER
                        bundleId = appBundleId
                    }
                    paths += listOf("Library", "Documents", "tmp", "SystemData")
                }
            )
        }
    }

    companion object {
        // 4Mb, the default max read for gRPC
        private const val CHUNK_SIZE = 1024 * 1024 * 3
        private val GSON = Gson()
        private val LOGGER = LoggerFactory.getLogger(IdbIOSDevice::class.java)
    }
}
