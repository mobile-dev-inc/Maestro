package dev.mobile.maestro

import android.app.UiAutomation
import android.content.Context
import android.content.Context.LOCATION_SERVICE
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent.KEYCODE_1
import android.view.KeyEvent.KEYCODE_4
import android.view.KeyEvent.KEYCODE_5
import android.view.KeyEvent.KEYCODE_6
import android.view.KeyEvent.KEYCODE_7
import android.view.KeyEvent.KEYCODE_APOSTROPHE
import android.view.KeyEvent.KEYCODE_AT
import java.util.concurrent.TimeUnit
import android.view.KeyEvent.KEYCODE_BACKSLASH
import android.view.KeyEvent.KEYCODE_COMMA
import android.view.KeyEvent.KEYCODE_EQUALS
import android.view.KeyEvent.KEYCODE_GRAVE
import android.view.KeyEvent.KEYCODE_LEFT_BRACKET
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_NUMPAD_ADD
import android.view.KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN
import android.view.KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN
import android.view.KeyEvent.KEYCODE_PERIOD
import android.view.KeyEvent.KEYCODE_POUND
import android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
import android.view.KeyEvent.KEYCODE_SEMICOLON
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_STAR
import android.view.KeyEvent.META_SHIFT_LEFT_ON
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiDeviceExt.clickExt
import com.google.android.gms.location.LocationServices
import dev.mobile.maestro.location.FusedLocationProvider
import dev.mobile.maestro.location.LocationManagerProvider
import dev.mobile.maestro.location.MockLocationProvider
import dev.mobile.maestro.location.PlayServices
import dev.mobile.maestro.screenshot.ScreenshotException
import dev.mobile.maestro.screenshot.ScreenshotService
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import maestro_android.MaestroAndroid
import maestro_android.MaestroDriverGrpc
import maestro_android.addMediaResponse
import maestro_android.checkWindowUpdatingResponse
import maestro_android.deviceInfo
import maestro_android.emptyResponse
import maestro_android.eraseAllTextResponse
import maestro_android.inputTextResponse
import maestro_android.launchAppResponse
import maestro_android.screenshotResponse
import maestro_android.setLocationResponse
import maestro_android.tapResponse
import maestro_android.viewHierarchyResponse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Timer
import java.util.TimerTask
import kotlin.system.measureTimeMillis

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class MaestroDriverService {

    @Test
    fun grpcServer() {
        Configurator.getInstance()
            .setActionAcknowledgmentTimeout(0L)
            .setWaitForIdleTimeout(0L)
            .setWaitForSelectorTimeout(0L)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiDevice = UiDevice.getInstance(instrumentation)
        val uiAutomation = instrumentation.uiAutomation

        val port = InstrumentationRegistry.getArguments().getString("port", "7001").toInt()

        println("Server running on port [ $port ]")

        NettyServerBuilder.forPort(port)
            .addService(Service(uiDevice, uiAutomation))
            .permitKeepAliveTime(30, TimeUnit.SECONDS) // If a client pings more than once every 30 seconds, terminate the connection
            .permitKeepAliveWithoutCalls(true) // Allow pings even when there are no active streams.
            .keepAliveTimeout(20, TimeUnit.SECONDS) // wait 20 seconds for client to ack the keep alive
            .maxConnectionIdle(30, TimeUnit.MINUTES) // If a client is idle for 30 minutes, send a GOAWAY frame.
            .build()
            .start()

        while (!Thread.interrupted()) {
            Thread.sleep(100)
        }
    }
}

class Service(
    private val uiDevice: UiDevice,
    private val uiAutomation: UiAutomation,
) : MaestroDriverGrpc.MaestroDriverImplBase() {

    private var locationTimerTask : TimerTask? = null
    private val locationTimer = Timer()

    private val screenshotService = ScreenshotService()
    private val mockLocationProviderList = mutableListOf<MockLocationProvider>()
    private val toastAccessibilityListener = ToastAccessibilityListener.start(uiAutomation)

    companion object {
        private const val TAG = "Maestro"
        private const val UPDATE_INTERVAL_IN_MILLIS = 2000L

        private val ERROR_TYPE_KEY: Metadata.Key<String> =
            Metadata.Key.of("error-type", Metadata.ASCII_STRING_MARSHALLER)
        private val ERROR_MSG_KEY: Metadata.Key<String> =
            Metadata.Key.of("error-message", Metadata.ASCII_STRING_MARSHALLER)
        private val ERROR_CAUSE_KEY: Metadata.Key<String> =
            Metadata.Key.of("error-cause", Metadata.ASCII_STRING_MARSHALLER)
    }

    override fun launchApp(
        request: MaestroAndroid.LaunchAppRequest,
        responseObserver: StreamObserver<MaestroAndroid.LaunchAppResponse>
    ) {
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext

            val intent = context.packageManager.getLaunchIntentForPackage(request.packageName)

            if (intent == null) {
                Log.e("Maestro", "No launcher intent found for package ${request.packageName}")
                responseObserver.onError(RuntimeException("No launcher intent found for package ${request.packageName}"))
                return
            }

            request.argumentsList
                .forEach {
                    when (it.type) {
                        String::class.java.name -> intent.putExtra(it.key, it.value)
                        Boolean::class.java.name -> intent.putExtra(it.key, it.value.toBoolean())
                        Int::class.java.name -> intent.putExtra(it.key, it.value.toInt())
                        Double::class.java.name -> intent.putExtra(it.key, it.value.toDouble())
                        Long::class.java.name -> intent.putExtra(it.key, it.value.toLong())
                        else -> intent.putExtra(it.key, it.value)
                    }
                }
            context.startActivity(intent)

            responseObserver.onNext(launchAppResponse { })
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    override fun deviceInfo(
        request: MaestroAndroid.DeviceInfoRequest,
        responseObserver: StreamObserver<MaestroAndroid.DeviceInfo>
    ) {
        try {
            val windowManager = InstrumentationRegistry.getInstrumentation()
                .context
                .getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)

            responseObserver.onNext(
                deviceInfo {
                    widthPixels = displayMetrics.widthPixels
                    heightPixels = displayMetrics.heightPixels
                }
            )
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    override fun viewHierarchy(
        request: MaestroAndroid.ViewHierarchyRequest,
        responseObserver: StreamObserver<MaestroAndroid.ViewHierarchyResponse>
    ) {
        try {
            refreshAccessibilityCache()
            val stream = ByteArrayOutputStream()

            val ms = measureTimeMillis {
                if (toastAccessibilityListener.getToastAccessibilityNode() != null && !toastAccessibilityListener.isTimedOut()) {
                    Log.d("Maestro", "Requesting view hierarchy with toast")
                    ViewHierarchy.dump(
                        uiDevice,
                        uiAutomation,
                        stream,
                        toastAccessibilityListener.getToastAccessibilityNode()
                    )
                } else {
                    Log.d("Maestro", "Requesting view hierarchy")
                    ViewHierarchy.dump(
                        uiDevice,
                        uiAutomation,
                        stream
                    )
                }
            }
            Log.d("Maestro", "View hierarchy received in $ms ms")

            responseObserver.onNext(
                viewHierarchyResponse {
                    hierarchy = stream.toString(Charsets.UTF_8.name())
                }
            )
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    /**
     * Clears the in-process Accessibility cache, removing any stale references. Because the
     * AccessibilityInteractionClient singleton stores copies of AccessibilityNodeInfo instances,
     * calls to public APIs such as `recycle` do not guarantee cached references get updated.
     */
    private fun refreshAccessibilityCache() {
        try {
            uiDevice.waitForIdle(500)
            uiAutomation.serviceInfo = null
        } catch (nullExp: NullPointerException) {
            /* no-op */
        }
    }

    override fun tap(
        request: MaestroAndroid.TapRequest,
        responseObserver: StreamObserver<MaestroAndroid.TapResponse>
    ) {
        try {
            uiDevice.clickExt(
                request.x,
                request.y
            )

            responseObserver.onNext(tapResponse {})
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    override fun addMedia(responseObserver: StreamObserver<MaestroAndroid.AddMediaResponse>): StreamObserver<MaestroAndroid.AddMediaRequest> {
        return object : StreamObserver<MaestroAndroid.AddMediaRequest> {

            var outputStream: OutputStream? = null

            override fun onNext(value: MaestroAndroid.AddMediaRequest) {
                if (outputStream == null) {
                    outputStream = MediaStorage.getOutputStream(
                        value.mediaName,
                        value.mediaExt
                    )
                }
                value.payload.data.writeTo(outputStream)
            }

            override fun onError(t: Throwable) {
                responseObserver.onError(t.internalError())
            }

            override fun onCompleted() {
                responseObserver.onNext(addMediaResponse { })
                responseObserver.onCompleted()
            }

        }
    }

    override fun eraseAllText(
        request: MaestroAndroid.EraseAllTextRequest,
        responseObserver: StreamObserver<MaestroAndroid.EraseAllTextResponse>
    ) {
        try {
            val charactersToErase = request.charactersToErase
            Log.d("Maestro", "Erasing text $charactersToErase")

            for (i in 1..charactersToErase) {
                uiDevice.pressDelete()
            }

            responseObserver.onNext(eraseAllTextResponse { })
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    override fun inputText(
        request: MaestroAndroid.InputTextRequest,
        responseObserver: StreamObserver<MaestroAndroid.InputTextResponse>
    ) {
        try {
            Log.d("Maestro", "Inputting text")
            request.text.forEach {
                setText(it.toString())
                Thread.sleep(75)
            }

            responseObserver.onNext(inputTextResponse { })
            responseObserver.onCompleted()
        } catch (e: Throwable) {
            responseObserver.onError(e.internalError())
        }
    }

    override fun screenshot(
        request: MaestroAndroid.ScreenshotRequest,
        responseObserver: StreamObserver<MaestroAndroid.ScreenshotResponse>
    ) {
        try {
            val bitmap = screenshotService.takeScreenshotWithRetry { uiAutomation.takeScreenshot() }
            val bytes = screenshotService.encodePng(bitmap)
            responseObserver.onNext(screenshotResponse { this.bytes = bytes })
            responseObserver.onCompleted()
        } catch (e: NullPointerException) {
            Log.e(TAG, "Screenshot failed with NullPointerException: ${e.message}", e)
            responseObserver.onError(e.internalError())
        } catch (e: ScreenshotException) {
            Log.e(TAG, "Screenshot failed with ScreenshotException: ${e.message}", e)
            responseObserver.onError(e.internalError())
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed with: ${e.message}", e)
            responseObserver.onError(e.internalError())
        }
    }

    override fun isWindowUpdating(
        request: MaestroAndroid.CheckWindowUpdatingRequest,
        responseObserver: StreamObserver<MaestroAndroid.CheckWindowUpdatingResponse>
    ) {
        try {
            responseObserver.onNext(checkWindowUpdatingResponse {
                isWindowUpdating = uiDevice.waitForWindowUpdate(request.appId, 500)
            })
            responseObserver.onCompleted()
        } catch (e: Throwable) {
            responseObserver.onError(e.internalError())
        }
    }

    override fun disableLocationUpdates(
        request: MaestroAndroid.EmptyRequest,
        responseObserver: StreamObserver<MaestroAndroid.EmptyResponse>
    ) {
        try {
            Log.d(TAG, "[Start] Disabling location updates")
            locationTimerTask?.cancel()
            locationTimer.cancel()
            mockLocationProviderList.forEach {
                it.disable()
            }
            Log.d(TAG, "[Done] Disabling location updates")
            responseObserver.onNext(emptyResponse {  })
            responseObserver.onCompleted()
        } catch (exception: Exception) {
            responseObserver.onError(exception.internalError())
        }
    }

    override fun enableMockLocationProviders(
        request: MaestroAndroid.EmptyRequest,
        responseObserver: StreamObserver<MaestroAndroid.EmptyResponse>
    ) {
        try {
            Log.d(TAG, "[Start] Enabling mock location providers")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager

            mockLocationProviderList.addAll(
                createMockProviders(context, locationManager)
            )

            mockLocationProviderList.forEach {
                it.enable()
            }
            Log.d(TAG, "[Done] Enabling mock location providers")

            responseObserver.onNext(emptyResponse {  })
            responseObserver.onCompleted()
        } catch (exception: Exception) {
            Log.e(TAG, "Error while enabling mock location provider", exception)
            responseObserver.onError(exception.internalError())
        }
    }

    private fun createMockProviders(
        context: Context,
        locationManager: LocationManager
    ): List<MockLocationProvider> {
        val playServices = PlayServices()
        val fusedLocationProvider: MockLocationProvider? = if (playServices.isAvailable(context)) {
            val fusedLocationProviderClient =
                LocationServices.getFusedLocationProviderClient(context)
            FusedLocationProvider(fusedLocationProviderClient)
        } else {
            null
        }
        return (locationManager.allProviders.mapNotNull {
            if (it.equals(LocationManager.PASSIVE_PROVIDER)) {
                null
            } else {
                val mockProvider = createLocationManagerMockProvider(locationManager, it)
                mockProvider
            }
        } + fusedLocationProvider).mapNotNull { it }
    }

    private fun createLocationManagerMockProvider(
        locationManager: LocationManager,
        providerName: String?
    ): MockLocationProvider? {
        if (providerName == null) {
            return null
        }
        // API level check for existence of provider properties
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API level 31 and above
            val providerProperties =
                locationManager.getProviderProperties(providerName) ?: return null
            return LocationManagerProvider(
                locationManager,
                providerName,
                providerProperties.hasNetworkRequirement(),
                providerProperties.hasSatelliteRequirement(),
                providerProperties.hasCellRequirement(),
                providerProperties.hasMonetaryCost(),
                providerProperties.hasAltitudeSupport(),
                providerProperties.hasSpeedSupport(),
                providerProperties.hasBearingSupport(),
                providerProperties.powerUsage,
                providerProperties.accuracy
            )
        }
        val provider = locationManager.getProvider(providerName) ?: return null
        return LocationManagerProvider(
            locationManager,
            provider.name,
            provider.requiresNetwork(),
            provider.requiresSatellite(),
            provider.requiresCell(),
            provider.hasMonetaryCost(),
            provider.supportsAltitude(),
            provider.supportsSpeed(),
            provider.supportsBearing(),
            provider.powerRequirement,
            provider.accuracy
        )
    }


    override fun setLocation(
        request: MaestroAndroid.SetLocationRequest,
        responseObserver: StreamObserver<MaestroAndroid.SetLocationResponse>
    ) {
        try {
            if (locationTimerTask != null) {
                locationTimerTask?.cancel()
            }

            locationTimerTask = object : TimerTask() {
                override fun run() {
                    mockLocationProviderList.forEach {
                        val latitude = request.latitude
                        val longitude = request.longitude
                        Log.d(TAG, "Setting location latitude: $latitude and longitude: $longitude for ${it.getProviderName()}")
                        val location = Location(it.getProviderName()).apply {
                            setLatitude(latitude)
                            setLongitude(longitude)
                            accuracy = Criteria.ACCURACY_FINE.toFloat()
                            altitude = 0.0
                            time = System.currentTimeMillis()
                            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        }
                        it.setLocation(location)
                    }
                }
            }
            locationTimer.schedule(
                locationTimerTask,
                0,
                UPDATE_INTERVAL_IN_MILLIS
            )
            responseObserver.onNext(setLocationResponse { })
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    private fun setText(text: String) {
        for (element in text) {
            Log.d("Maestro", element.code.toString())
            when (element.code) {
                in 48..57 -> {
                    /** 0~9 **/
                    uiDevice.pressKeyCode(element.code - 41)
                }

                in 65..90 -> {
                    /** A~Z **/
                    uiDevice.pressKeyCode(element.code - 36, 1)
                }

                in 97..122 -> {
                    /** a~z **/
                    uiDevice.pressKeyCode(element.code - 68)
                }

                ';'.code -> uiDevice.pressKeyCode(KEYCODE_SEMICOLON)
                '='.code -> uiDevice.pressKeyCode(KEYCODE_EQUALS)
                ','.code -> uiDevice.pressKeyCode(KEYCODE_COMMA)
                '-'.code -> uiDevice.pressKeyCode(KEYCODE_MINUS)
                '.'.code -> uiDevice.pressKeyCode(KEYCODE_PERIOD)
                '/'.code -> uiDevice.pressKeyCode(KEYCODE_SLASH)
                '`'.code -> uiDevice.pressKeyCode(KEYCODE_GRAVE)
                '\''.code -> uiDevice.pressKeyCode(KEYCODE_APOSTROPHE)
                '['.code -> uiDevice.pressKeyCode(KEYCODE_LEFT_BRACKET)
                ']'.code -> uiDevice.pressKeyCode(KEYCODE_RIGHT_BRACKET)
                '\\'.code -> uiDevice.pressKeyCode(KEYCODE_BACKSLASH)
                ' '.code -> uiDevice.pressKeyCode(KEYCODE_SPACE)
                '@'.code -> uiDevice.pressKeyCode(KEYCODE_AT)
                '#'.code -> uiDevice.pressKeyCode(KEYCODE_POUND)
                '*'.code -> uiDevice.pressKeyCode(KEYCODE_STAR)
                '('.code -> uiDevice.pressKeyCode(KEYCODE_NUMPAD_LEFT_PAREN)
                ')'.code -> uiDevice.pressKeyCode(KEYCODE_NUMPAD_RIGHT_PAREN)
                '+'.code -> uiDevice.pressKeyCode(KEYCODE_NUMPAD_ADD)
                '!'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_1)
                '$'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_4)
                '%'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_5)
                '^'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_6)
                '&'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_7)
                '"'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_APOSTROPHE)
                '{'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_LEFT_BRACKET)
                '}'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_RIGHT_BRACKET)
                ':'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_SEMICOLON)
                '|'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_BACKSLASH)
                '<'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_COMMA)
                '>'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_PERIOD)
                '?'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_SLASH)
                '~'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_GRAVE)
                '_'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_MINUS)
            }
        }
    }

    private fun keyPressShiftedToEvents(uiDevice: UiDevice, keyCode: Int) {
        uiDevice.pressKeyCode(keyCode, META_SHIFT_LEFT_ON)
    }

    internal fun Throwable.internalError(): StatusRuntimeException {
        val trailers = Metadata().apply {
            put(ERROR_TYPE_KEY, this@internalError::class.java.name)
            this@internalError.message?.let { put(ERROR_MSG_KEY, it) }
            this@internalError.cause?.let { put(ERROR_CAUSE_KEY, it.toString()) }
        }
        return Status.INTERNAL.withDescription(message).asRuntimeException(trailers)
    }

    enum class FileType(val ext: String, val mimeType: String) {
        JPG("jpg", "image/jpg"),
        JPEG("jpeg", "image/jpeg"),
        PNG("png", "image/png"),
        GIF("gif", "image/gif"),
        MP4("mp4", "video/mp4"),
    }
}
