import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Opens the native iOS photo pickers (`UIImagePickerController` and
/// `PHPickerViewController`, presented in `AppDelegate.swift`). On iOS 26+ both
/// run in a separate process, and opening one while the keyboard is up leaves
/// its photos out of the app's view hierarchy. iOS only.
class PhotoPickerScreen extends StatefulWidget {
  const PhotoPickerScreen({super.key});

  @override
  State<PhotoPickerScreen> createState() => _PhotoPickerScreenState();
}

class _PhotoPickerScreenState extends State<PhotoPickerScreen> {
  static const _channel = MethodChannel('com.example.demo_app/photo_picker');

  String _result = 'No photo picked';

  Future<void> _pick(String method) async {
    setState(() => _result = 'No photo picked');
    final picked = await _channel.invokeMethod<bool>(method) ?? false;
    setState(() => _result = picked ? 'Photo picked' : 'No photo picked');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Photo Picker')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            Semantics(
              identifier: 'caption',
              child: const TextField(decoration: InputDecoration(hintText: 'Caption')),
            ),
            const SizedBox(height: 12),
            ElevatedButton(
              onPressed: () => _pick('pickWithImagePicker'),
              child: const Text('UIImagePickerController'),
            ),
            ElevatedButton(
              onPressed: () => _pick('pickWithPHPicker'),
              child: const Text('PHPickerViewController'),
            ),
            const SizedBox(height: 12),
            Text(_result),
          ],
        ),
      ),
    );
  }
}
