import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class OrientationScreen extends StatefulWidget {
  const OrientationScreen({super.key});

  @override
  State<OrientationScreen> createState() => _OrientationScreenState();
}

class _OrientationScreenState extends State<OrientationScreen> with WidgetsBindingObserver {
  static const _channel = MethodChannel('com.example.example/rotation');

  String _label = 'Portrait';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _updateRotation();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeMetrics() {
    _updateRotation();
  }

  Future<void> _updateRotation() async {
    final rotation = await _channel.invokeMethod<int>('getRotation');
    final label = switch (rotation) {
      0 => 'Portrait',
      1 => 'Landscape Left',
      2 => 'Portrait Upside Down',
      3 => 'Landscape Right',
      _ => 'Unknown',
    };
    if (mounted) {
      setState(() => _label = label);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Orientation Test')),
      body: Center(
        child: Text(
          _label,
          style: Theme.of(context).textTheme.headlineMedium,
        ),
      ),
    );
  }
}
