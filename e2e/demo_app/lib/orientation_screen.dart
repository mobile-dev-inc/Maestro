import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class OrientationScreen extends StatefulWidget {
  const OrientationScreen({super.key});

  @override
  State<OrientationScreen> createState() => _OrientationScreenState();
}

class _OrientationScreenState extends State<OrientationScreen>
    with WidgetsBindingObserver {
  static const _channel = MethodChannel('com.example.demo_app/orientation');
  String _orientation = 'Unknown';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _fetchOrientation();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeMetrics() {
    _fetchOrientation();
  }

  Future<void> _fetchOrientation() async {
    final result = await _channel.invokeMethod<String>('getOrientation');
    if (result != null && mounted) {
      setState(() => _orientation = result);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Orientation Test'),
      ),
      body: Center(
        child: Semantics(
          identifier: 'orientationLabel',
          child: Text(
            _orientation,
            style: Theme.of(context).textTheme.headlineLarge,
          ),
        ),
      ),
    );
  }
}
