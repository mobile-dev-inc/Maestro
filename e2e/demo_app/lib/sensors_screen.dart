import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:sensors_plus/sensors_plus.dart';
import 'package:geolocator/geolocator.dart';

class SensorsScreen extends StatefulWidget {
  const SensorsScreen({super.key});

  @override
  State<SensorsScreen> createState() => _SensorsScreenState();
}

class _SensorsScreenState extends State<SensorsScreen> {
  // Accelerometer
  AccelerometerEvent? _accelerometerEvent;
  bool _accelerometerAvailable = false;
  StreamSubscription<AccelerometerEvent>? _accelerometerSubscription;

  // Gyroscope
  GyroscopeEvent? _gyroscopeEvent;
  bool _gyroscopeAvailable = false;
  StreamSubscription<GyroscopeEvent>? _gyroscopeSubscription;

  // Magnetometer
  MagnetometerEvent? _magnetometerEvent;
  bool _magnetometerAvailable = false;
  StreamSubscription<MagnetometerEvent>? _magnetometerSubscription;

  // Compass (calculated from magnetometer)
  double? _compassHeading;
  bool _compassAvailable = false;

  // GPS/Location
  Position? _position;
  bool _locationAvailable = false;
  bool _locationPermissionRequested = false;
  String? _locationError;

  // User Accelerometer (gravity removed)
  UserAccelerometerEvent? _userAccelerometerEvent;
  bool _userAccelerometerAvailable = false;
  StreamSubscription<UserAccelerometerEvent>? _userAccelerometerSubscription;

  // Barometer (Android platform channel)
  double? _barometerValue;
  bool _barometerAvailable = false;
  StreamSubscription<dynamic>? _barometerSubscription;
  static const MethodChannel _methodChannel = MethodChannel('com.example.example/sensors');
  static const EventChannel _barometerChannel = EventChannel('com.example.example/barometer');

  // Light Sensor (Android platform channel)
  double? _lightValue;
  bool _lightAvailable = false;
  StreamSubscription<dynamic>? _lightSubscription;
  static const EventChannel _lightChannel = EventChannel('com.example.example/light');

  // Proximity Sensor (Android platform channel)
  double? _proximityValue;
  bool _proximityAvailable = false;
  StreamSubscription<dynamic>? _proximitySubscription;
  static const EventChannel _proximityChannel = EventChannel('com.example.example/proximity');

  @override
  void initState() {
    super.initState();
    _initializeSensors();
  }

  void _initializeSensors() {
    // Accelerometer
    try {
      _accelerometerSubscription = accelerometerEventStream().listen(
        (event) {
          setState(() {
            _accelerometerEvent = event;
            _accelerometerAvailable = true;
          });
        },
        onError: (error) {
          setState(() {
            _accelerometerAvailable = false;
          });
        },
        cancelOnError: false,
      );
    } catch (e) {
      setState(() {
        _accelerometerAvailable = false;
      });
    }

    // Gyroscope
    try {
      _gyroscopeSubscription = gyroscopeEventStream().listen(
        (event) {
          setState(() {
            _gyroscopeEvent = event;
            _gyroscopeAvailable = true;
          });
        },
        onError: (error) {
          setState(() {
            _gyroscopeAvailable = false;
          });
        },
        cancelOnError: false,
      );
    } catch (e) {
      setState(() {
        _gyroscopeAvailable = false;
      });
    }

    // Magnetometer
    try {
      _magnetometerSubscription = magnetometerEventStream().listen(
        (event) {
          setState(() {
            _magnetometerEvent = event;
            _magnetometerAvailable = true;
            _updateCompassHeading(event);
          });
        },
        onError: (error) {
          setState(() {
            _magnetometerAvailable = false;
            _compassAvailable = false;
          });
        },
        cancelOnError: false,
      );
    } catch (e) {
      setState(() {
        _magnetometerAvailable = false;
        _compassAvailable = false;
      });
    }

    // User Accelerometer
    try {
      _userAccelerometerSubscription = userAccelerometerEventStream().listen(
        (event) {
          setState(() {
            _userAccelerometerEvent = event;
            _userAccelerometerAvailable = true;
          });
        },
        onError: (error) {
          setState(() {
            _userAccelerometerAvailable = false;
          });
        },
        cancelOnError: false,
      );
    } catch (e) {
      setState(() {
        _userAccelerometerAvailable = false;
      });
    }

    // GPS/Location
    _initLocation();

    // Platform channel sensors (Android)
    _initPlatformSensors();
  }

  void _updateCompassHeading(MagnetometerEvent event) {
    final heading = math.atan2(event.y, event.x) * (180 / math.pi);
    final normalizedHeading = (heading + 360) % 360;
    setState(() {
      _compassHeading = normalizedHeading;
      _compassAvailable = true;
    });
  }

  Future<void> _initLocation() async {
    if (_locationPermissionRequested) return;
    _locationPermissionRequested = true;

    bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) {
      setState(() {
        _locationError = 'Location services disabled';
        _locationAvailable = false;
      });
      return;
    }

    LocationPermission permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
      if (permission == LocationPermission.denied) {
        setState(() {
          _locationError = 'Location permission denied';
          _locationAvailable = false;
        });
        return;
      }
    }

    if (permission == LocationPermission.deniedForever) {
      setState(() {
        _locationError = 'Location permission permanently denied';
        _locationAvailable = false;
      });
      return;
    }

    try {
      Position position = await Geolocator.getCurrentPosition();
      setState(() {
        _position = position;
        _locationAvailable = true;
        _locationError = null;
      });

      Geolocator.getPositionStream().listen(
        (position) {
          setState(() {
            _position = position;
            _locationAvailable = true;
          });
        },
        onError: (error) {
          setState(() {
            _locationError = error.toString();
            _locationAvailable = false;
          });
        },
      );
    } catch (e) {
      setState(() {
        _locationError = e.toString();
        _locationAvailable = false;
      });
    }
  }

  void _initPlatformSensors() {
    _initBarometer();
    _initLightSensor();
    _initProximitySensor();
  }

  Future<void> _initBarometer() async {
    try {
      final available = await _methodChannel.invokeMethod<bool>('isBarometerAvailable') ?? false;
      if (!available) {
        setState(() {
          _barometerAvailable = false;
        });
        return;
      }

      setState(() {
        _barometerAvailable = true;
      });

      _barometerSubscription = _barometerChannel.receiveBroadcastStream().listen(
        (data) {
          setState(() {
            _barometerValue = (data as num).toDouble();
          });
        },
        onError: (error) {
          setState(() {
            _barometerAvailable = false;
            _barometerValue = null;
          });
        },
        cancelOnError: false,
      );
    } catch (e) {
      setState(() {
        _barometerAvailable = false;
      });
    }
  }

  Future<void> _initLightSensor() async {
    try {
      final available = await _methodChannel.invokeMethod<bool>('isLightSensorAvailable') ?? false;
      if (!available) {
        setState(() {
          _lightAvailable = false;
        });
        return;
      }

      setState(() {
        _lightAvailable = true;
      });

      _lightSubscription = _lightChannel.receiveBroadcastStream().listen(
        (data) {
          setState(() {
            _lightValue = (data as num).toDouble();
          });
        },
        onError: (error) {
          setState(() {
            _lightAvailable = false;
            _lightValue = null;
          });
        },
        cancelOnError: false,
      );
    } catch (e) {
      setState(() {
        _lightAvailable = false;
      });
    }
  }

  Future<void> _initProximitySensor() async {
    try {
      final available = await _methodChannel.invokeMethod<bool>('isProximitySensorAvailable') ?? false;
      if (!available) {
        setState(() {
          _proximityAvailable = false;
        });
        return;
      }

      setState(() {
        _proximityAvailable = true;
      });

      _proximitySubscription = _proximityChannel.receiveBroadcastStream().listen(
        (data) {
          setState(() {
            _proximityValue = (data as num).toDouble();
          });
        },
        onError: (error) {
          setState(() {
            _proximityAvailable = false;
            _proximityValue = null;
          });
        },
        cancelOnError: false,
      );
    } catch (e) {
      setState(() {
        _proximityAvailable = false;
      });
    }
  }

  @override
  void dispose() {
    _accelerometerSubscription?.cancel();
    _gyroscopeSubscription?.cancel();
    _magnetometerSubscription?.cancel();
    _userAccelerometerSubscription?.cancel();
    _barometerSubscription?.cancel();
    _lightSubscription?.cancel();
    _proximitySubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Sensors'),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Sensor readings from device/emulator',
              style: TextStyle(fontSize: 14, color: Colors.grey),
            ),
            const SizedBox(height: 16),
            _buildSectionTitle('Motion Sensors'),
            _buildSensorCard(
              'Accelerometer',
              _accelerometerAvailable,
              _accelerometerEvent != null
                  ? 'X: ${_accelerometerEvent!.x.toStringAsFixed(2)} m/s²\n'
                      'Y: ${_accelerometerEvent!.y.toStringAsFixed(2)} m/s²\n'
                      'Z: ${_accelerometerEvent!.z.toStringAsFixed(2)} m/s²'
                  : null,
            ),
            _buildSensorCard(
              'User Accelerometer',
              _userAccelerometerAvailable,
              _userAccelerometerEvent != null
                  ? 'X: ${_userAccelerometerEvent!.x.toStringAsFixed(2)} m/s²\n'
                      'Y: ${_userAccelerometerEvent!.y.toStringAsFixed(2)} m/s²\n'
                      'Z: ${_userAccelerometerEvent!.z.toStringAsFixed(2)} m/s²'
                  : null,
            ),
            _buildSensorCard(
              'Gyroscope',
              _gyroscopeAvailable,
              _gyroscopeEvent != null
                  ? 'X: ${_gyroscopeEvent!.x.toStringAsFixed(2)} rad/s\n'
                      'Y: ${_gyroscopeEvent!.y.toStringAsFixed(2)} rad/s\n'
                      'Z: ${_gyroscopeEvent!.z.toStringAsFixed(2)} rad/s'
                  : null,
            ),
            const SizedBox(height: 16),
            _buildSectionTitle('Orientation Sensors'),
            _buildSensorCard(
              'Compass',
              _compassAvailable,
              _compassHeading != null
                  ? 'Heading: ${_compassHeading!.toStringAsFixed(1)}°'
                  : null,
            ),
            _buildSensorCard(
              'Magnetometer',
              _magnetometerAvailable,
              _magnetometerEvent != null
                  ? 'X: ${_magnetometerEvent!.x.toStringAsFixed(2)} μT\n'
                      'Y: ${_magnetometerEvent!.y.toStringAsFixed(2)} μT\n'
                      'Z: ${_magnetometerEvent!.z.toStringAsFixed(2)} μT'
                  : null,
            ),
            const SizedBox(height: 16),
            _buildSectionTitle('Environmental Sensors'),
            _buildSensorCard(
              'Barometer',
              _barometerAvailable,
              _barometerValue != null
                  ? 'Pressure: ${_barometerValue!.toStringAsFixed(2)} hPa'
                  : null,
            ),
            _buildSensorCard(
              'Light Sensor',
              _lightAvailable,
              _lightValue != null
                  ? 'Ambient Light: ${_lightValue!.toStringAsFixed(2)} lux'
                  : null,
            ),
            _buildSensorCard(
              'Proximity Sensor',
              _proximityAvailable,
              _proximityValue != null
                  ? 'Distance: ${_proximityValue!.toStringAsFixed(2)} cm'
                  : null,
            ),
            const SizedBox(height: 16),
            _buildSectionTitle('Location'),
            _buildSensorCard(
              'GPS',
              _locationAvailable,
              _position != null
                  ? 'Latitude: ${_position!.latitude.toStringAsFixed(6)}\n'
                      'Longitude: ${_position!.longitude.toStringAsFixed(6)}\n'
                      'Accuracy: ${_position!.accuracy.toStringAsFixed(1)} m\n'
                      'Altitude: ${_position!.altitude.toStringAsFixed(1)} m'
                  : null,
              error: _locationError,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12, top: 8),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 20,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }

  Widget _buildSensorCard(
    String sensorName,
    bool available,
    String? value, {
    String? error,
  }) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  sensorName,
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: available ? Colors.green[100] : Colors.red[100],
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(
                    available
                        ? '$sensorName Available'
                        : '$sensorName Not Available',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                      color: available ? Colors.green[800] : Colors.red[800],
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (available && value != null)
              Text(
                value,
                style: const TextStyle(
                  fontSize: 14,
                  fontFamily: 'monospace',
                ),
              )
            else if (error != null)
              Text(
                'Error: $error',
                style: TextStyle(
                  fontSize: 12,
                  color: Colors.red[700],
                ),
              )
            else if (available)
              const Text(
                'Sensor available, waiting for first reading...',
                style: TextStyle(
                  fontSize: 12,
                  color: Colors.orange,
                  fontStyle: FontStyle.italic,
                ),
              )
            else
              const Text(
                'Sensor not available on this device',
                style: TextStyle(
                  fontSize: 12,
                  color: Colors.grey,
                ),
              ),
          ],
        ),
      ),
    );
  }
}