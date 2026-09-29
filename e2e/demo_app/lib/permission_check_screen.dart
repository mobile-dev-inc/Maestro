import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

/// Passively reports the status of runtime permissions WITHOUT ever requesting
/// them. Reading `Permission.<x>.status` does not trigger a system dialog, so
/// this screen deterministically reflects whatever state a preceding
/// `launchApp { permissions: }` / `setPermissions` left the app in.
///
/// This makes it a clean discriminator for permission tests (e.g. verifying
/// that permission values support variable interpolation). Contrast with the
/// Location Test screen, which actively calls `requestPermission()` and so
/// pops a dialog that races with the test.
class PermissionCheckScreen extends StatefulWidget {
  const PermissionCheckScreen({super.key});

  @override
  State<PermissionCheckScreen> createState() => _PermissionCheckScreenState();
}

class _PermissionCheckScreenState extends State<PermissionCheckScreen> {
  static const _permissions = <String, Permission>{
    'Location': Permission.location,
    'All Files': Permission.manageExternalStorage,
  };

  final Map<String, String> _statuses = {
    for (final name in _permissions.keys) name: 'Checking...',
  };

  @override
  void initState() {
    super.initState();
    _check();
  }

  Future<void> _check() async {
    for (final entry in _permissions.entries) {
      final granted = await entry.value.isGranted;
      if (!mounted) return;
      setState(() {
        _statuses[entry.key] = granted ? 'Allowed' : 'Not allowed';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Permission Check'),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (final name in _permissions.keys)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Semantics(
                  identifier: 'permissionStatus_$name',
                  child: Text(
                    '$name: ${_statuses[name]}',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ),
            const SizedBox(height: 20),
            Semantics(
              identifier: 'refreshPermissionButton',
              child: ElevatedButton(
                onPressed: _check,
                child: const Text('Refresh'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
