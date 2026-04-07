import 'dart:async';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';

class LocationScreen extends StatefulWidget {
  const LocationScreen({super.key});

  @override
  State<LocationScreen> createState() => _LocationScreenState();
}

class _LocationScreenState extends State<LocationScreen> {
  Position? _position;
  String? _error;
  StreamSubscription<Position>? _subscription;

  @override
  void initState() {
    super.initState();
    _startLocationUpdates();
  }

  Future<void> _startLocationUpdates() async {
    final serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) {
      setState(() => _error = 'Location services disabled');
      return;
    }

    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
      if (permission == LocationPermission.denied) {
        setState(() => _error = 'Location permission denied');
        return;
      }
    }

    if (permission == LocationPermission.deniedForever) {
      setState(() => _error = 'Location permission permanently denied');
      return;
    }

    try {
      final position = await Geolocator.getCurrentPosition();
      setState(() {
        _position = position;
        _error = null;
      });

      _subscription = Geolocator.getPositionStream(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.best,
          distanceFilter: 0,
        ),
      ).listen(
        (pos) => setState(() {
          _position = pos;
          _error = null;
        }),
        onError: (e) => setState(() => _error = e.toString()),
      );
    } catch (e) {
      setState(() => _error = e.toString());
    }
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Location Test'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (_error != null)
              Text(
                'Error: $_error',
                style: const TextStyle(color: Colors.red),
              )
            else if (_position == null)
              const Text('Waiting for location...')
            else ...[
              Text(
                'Latitude: ${_position!.latitude.toStringAsFixed(6)}',
              ),
              const SizedBox(height: 8),
              Text(
                'Longitude: ${_position!.longitude.toStringAsFixed(6)}',
              ),
              const SizedBox(height: 8),
              Text(
                'Accuracy: ${_position!.accuracy.toStringAsFixed(1)} m',
              ),
            ],
          ],
        ),
      ),
    );
  }
}