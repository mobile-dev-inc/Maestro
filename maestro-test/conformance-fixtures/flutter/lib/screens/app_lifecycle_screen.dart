import 'package:flutter/material.dart';
import '../emitter.dart';

/// launchApp / stopApp / killApp / clearAppState / openLink / backPress.
/// STATE (seeded) is emitted natively on launch and the flag persists in SharedPreferences (so
/// clearAppState has data to wipe); LIFECYCLE/DEEPLINK/BACK come from MainActivity. This screen just
/// renders the seed button, which calls the native seedState() over the channel.
class AppLifecycleScreen extends StatelessWidget {
  const AppLifecycleScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Semantics(
        identifier: 'state_seed_button',
        label: 'state_seed_button',
        button: true,
        child: ElevatedButton(
          onPressed: () => seedState(),
          child: const Text('Seed State'),
        ),
      ),
    );
  }
}
