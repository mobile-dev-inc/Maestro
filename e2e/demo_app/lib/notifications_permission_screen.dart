import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

class NotificationsPermissionScreen extends StatefulWidget {
  const NotificationsPermissionScreen({super.key});

  @override
  State<NotificationsPermissionScreen> createState() =>
      _NotificationsPermissionScreenState();
}

class _NotificationsPermissionScreenState
    extends State<NotificationsPermissionScreen> {
  String _permissionStatus = 'Not requested';

  Future<void> _requestNotificationPermission() async {
    final status = await Permission.notification.request();
    setState(() {
      _permissionStatus = status.toString();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Notifications Permission'),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Semantics(
              identifier: 'permissionStatus',
              child: Text(
                'Status: $_permissionStatus',
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            const SizedBox(height: 20),
            Semantics(
              identifier: 'requestPermissionButton',
              child: ElevatedButton(
                onPressed: _requestNotificationPermission,
                child: const Text('Request Notification Permission'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}