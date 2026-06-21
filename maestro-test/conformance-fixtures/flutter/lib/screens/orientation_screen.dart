import 'package:flutter/material.dart';

/// Orientation display only. The ORIENTATION event is emitted natively from MainActivity's
/// onConfigurationChanged (the activity declares configChanges so it isn't recreated on rotate).
class OrientationScreen extends StatelessWidget {
  const OrientationScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final o = MediaQuery.of(context).orientation;
    final value = o == Orientation.landscape ? 'LANDSCAPE' : 'PORTRAIT';
    return Center(
      child: Semantics(
        identifier: 'orientation_value',
        child: Text('Orientation: $value', style: const TextStyle(fontSize: 20)),
      ),
    );
  }
}
