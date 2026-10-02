import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Ends the app's process on demand, so Maestro's app-crash detection has something real to
/// detect. iOS only: the ending happens in native code (`AppDelegate.swift`), because an uncaught
/// Dart exception does not end the process.
///
/// [endOnOpen] ends the app as soon as the screen opens, the way a launch argument asks for
/// (`crashScreen: crash` or `crashScreen: exit`), for callers that cannot tap.
class CrashScreen extends StatefulWidget {
  const CrashScreen({super.key, this.endOnOpen});

  final String? endOnOpen;

  @override
  State<CrashScreen> createState() => _CrashScreenState();
}

class _CrashScreenState extends State<CrashScreen> {
  static const _channel = MethodChannel('com.example.demo_app/crash');

  @override
  void initState() {
    super.initState();
    final ending = widget.endOnOpen;
    if (ending != null) {
      // After the first frame, so the app is up and on this screen when it ends.
      WidgetsBinding.instance.addPostFrameCallback((_) => _channel.invokeMethod(ending));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Crash test screen')),
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ElevatedButton(
              onPressed: () => _channel.invokeMethod('crash'),
              child: const Text('Crash the app'),
            ),
            const SizedBox(height: 12),
            ElevatedButton(
              onPressed: () => _channel.invokeMethod('exit'),
              child: const Text('Exit the app'),
            ),
          ],
        ),
      ),
    );
  }
}
