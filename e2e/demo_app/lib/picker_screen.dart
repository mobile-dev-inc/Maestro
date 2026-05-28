import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// The Picker Test "screen" is just a button that opens the native iOS
/// PickerTestViewController via a method channel. We don't host the picker
/// in Flutter because CupertinoPicker doesn't render as a real UIPickerView
/// on iOS — XCTest's pickerWheels query can't find it.
///
/// The setPickerValue e2e test (`.maestro/issues/setPickerValue.yaml`) drives
/// the native picker that this screen launches.
class PickerScreen extends StatelessWidget {
  const PickerScreen({super.key});

  static const _channel = MethodChannel('com.example.demo_app/picker_test');

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Picker Test')),
      body: Center(
        child: ElevatedButton(
          onPressed: () {
            _channel.invokeMethod('openPickerTest');
          },
          child: const Text('Open Native Picker'),
        ),
      ),
    );
  }
}
