import 'package:flutter/material.dart';
import '../emitter.dart';

/// tap → GestureDetector.onTap; longPress → onLongPress. Each target is wrapped in Semantics so it
/// surfaces in the accessibility tree with identifier→resource-id and label→content-desc.
class TapScreen extends StatelessWidget {
  const TapScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Semantics(
            identifier: 'tap_target',
            label: 'tap_target',
            button: true,
            container: true,
            child: GestureDetector(
              onTap: () => emit('TAP', {'target': 'tap_target'}),
              child: _box('tap'),
            ),
          ),
          const SizedBox(height: 24),
          Semantics(
            identifier: 'longpress_target',
            label: 'longpress_target',
            container: true,
            child: GestureDetector(
              onLongPress: () =>
                  emit('LONG_PRESS', {'target': 'longpress_target', 'downMs': 3000}),
              child: _box('longpress'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _box(String t) => Container(
        width: 220,
        height: 64,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: const Color(0xFFCFE3FF),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Text(t),
      );
}
