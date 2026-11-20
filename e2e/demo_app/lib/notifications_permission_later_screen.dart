import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

/// Asks for notification permission a moment after "Ask for notifications" is
/// tapped, with no further tap from the flow, and counts every touch that reaches
/// the app while the alert is up. Maestro's auto-tap on the alert must not leak a
/// touch through to the app underneath, so a flow can assert the count stays at 0.
class NotificationsPermissionLaterScreen extends StatefulWidget {
  const NotificationsPermissionLaterScreen({super.key});

  @override
  State<NotificationsPermissionLaterScreen> createState() =>
      _NotificationsPermissionLaterScreenState();
}

class _NotificationsPermissionLaterScreenState
    extends State<NotificationsPermissionLaterScreen> {
  String _permissionStatus = 'Not requested';
  bool _alertUp = false;
  int _tapsDuringAlert = 0;

  // Ask a moment later, while the flow is already polling the screen, the way an
  // app asks after a login or a network call finishes.
  void _askLater() {
    Future.delayed(const Duration(seconds: 2), () async {
      if (!mounted) return;
      setState(() => _alertUp = true);
      final status = await Permission.notification.request();
      if (!mounted) return;
      setState(() {
        _alertUp = false;
        _permissionStatus = status.toString();
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Notifications Permission Later'),
      ),
      // A Listener, unlike a GestureDetector, adds no semantics node, so the
      // texts below stay separate elements in the view hierarchy.
      body: Listener(
        behavior: HitTestBehavior.opaque,
        onPointerDown: (_) {
          if (_alertUp) setState(() => _tapsDuringAlert++);
        },
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Semantics(
                identifier: 'note',
                child: const TextField(
                  decoration: InputDecoration(hintText: 'Note'),
                ),
              ),
              const SizedBox(height: 20),
              ElevatedButton(
                onPressed: _askLater,
                child: const Text('Ask for notifications'),
              ),
              const SizedBox(height: 20),
              Semantics(
                identifier: 'permissionStatus',
                child: Text(
                  'Status: $_permissionStatus',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
              const SizedBox(height: 20),
              Semantics(
                identifier: 'tapsDuringAlert',
                child: Text('Taps during alert: $_tapsDuringAlert'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
