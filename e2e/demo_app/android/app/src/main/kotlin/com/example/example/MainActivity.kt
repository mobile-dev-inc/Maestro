package com.example.example

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.example.example/sensors"
    private val BAROMETER_CHANNEL = "com.example.example/barometer"
    private val LIGHT_CHANNEL = "com.example.example/light"
    private val PROXIMITY_CHANNEL = "com.example.example/proximity"

    private var sensorManager: SensorManager? = null
    private var barometerListener: SensorEventListener? = null
    private var lightListener: SensorEventListener? = null
    private var proximityListener: SensorEventListener? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Method channel to check sensor availability
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "isBarometerAvailable" -> {
                    val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
                    result.success(sensor != null)
                }
                "isLightSensorAvailable" -> {
                    val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
                    result.success(sensor != null)
                }
                "isProximitySensorAvailable" -> {
                    val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                    result.success(sensor != null)
                }
                else -> result.notImplemented()
            }
        }

        // Barometer event channel
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, BAROMETER_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
                    if (sensor == null) {
                        events?.error("SENSOR_UNAVAILABLE", "Barometer sensor not available", null)
                        return
                    }

                    barometerListener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            if (event.sensor.type == Sensor.TYPE_PRESSURE) {
                                events?.success(event.values[0])
                            }
                        }
                        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                    }
                    sensorManager?.registerListener(barometerListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                }

                override fun onCancel(arguments: Any?) {
                    barometerListener?.let { sensorManager?.unregisterListener(it) }
                    barometerListener = null
                }
            }
        )

        // Light sensor event channel
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, LIGHT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
                    if (sensor == null) {
                        events?.error("SENSOR_UNAVAILABLE", "Light sensor not available", null)
                        return
                    }

                    lightListener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                                events?.success(event.values[0])
                            }
                        }
                        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                    }
                    sensorManager?.registerListener(lightListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                }

                override fun onCancel(arguments: Any?) {
                    lightListener?.let { sensorManager?.unregisterListener(it) }
                    lightListener = null
                }
            }
        )

        // Proximity sensor event channel
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, PROXIMITY_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                    if (sensor == null) {
                        events?.error("SENSOR_UNAVAILABLE", "Proximity sensor not available", null)
                        return
                    }

                    proximityListener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
                                events?.success(event.values[0])
                            }
                        }
                        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                    }
                    sensorManager?.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                }

                override fun onCancel(arguments: Any?) {
                    proximityListener?.let { sensorManager?.unregisterListener(it) }
                    proximityListener = null
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        barometerListener?.let { sensorManager?.unregisterListener(it) }
        lightListener?.let { sensorManager?.unregisterListener(it) }
        proximityListener?.let { sensorManager?.unregisterListener(it) }
    }
}